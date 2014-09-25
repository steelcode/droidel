package edu.colorado.droidel.codegen

import java.io.StringWriter
import com.squareup.javawriter.JavaWriter
import java.io.File
import scala.collection.JavaConversions._
import edu.colorado.droidel.constants.DroidelConstants._
import java.util.EnumSet
import javax.lang.model.element.Modifier.FINAL
import javax.lang.model.element.Modifier.PRIVATE
import javax.lang.model.element.Modifier.PUBLIC
import javax.lang.model.element.Modifier.STATIC
import edu.colorado.droidel.util.ClassUtil
import com.ibm.wala.types.TypeReference
import com.ibm.wala.types.ClassLoaderReference
import com.ibm.wala.ipa.cha.IClassHierarchy
import java.io.FileWriter
import AndroidSystemServiceStubGenerator._
import edu.colorado.droidel.util.JavaUtil
import edu.colorado.droidel.util.Types._
import edu.colorado.droidel.constants.AndroidConstants
import com.ibm.wala.types.MethodReference
import com.ibm.wala.shrikeBT.MethodEditor.Output
import com.ibm.wala.shrikeBT.PopInstruction
import com.ibm.wala.shrikeBT.InvokeInstruction
import com.ibm.wala.shrikeBT.IInvokeInstruction
import com.ibm.wala.shrikeBT.SwapInstruction
import com.ibm.wala.ssa.SSAInvokeInstruction
import com.ibm.wala.ssa.SymbolTable
import edu.colorado.droidel.util.CHAUtil
import com.ibm.wala.classLoader.IMethod
import com.ibm.wala.ssa.IR

object AndroidSystemServiceStubGenerator {
  val DEBUG = false
}

class AndroidSystemServiceStubGenerator(cha : IClassHierarchy, androidJarPath : String) extends AndroidStubGenerator {  
  
  val SYSTEM_SERVICES_MAP = Map(
    "accessibility" -> "android.view.accessibility.AccessibilityManager",
    "account" -> "android.accounts.AccountManager",
    "activity" -> "android.app.ActivityManager",
    "alarm" -> "android.app.AlarmManager",
    //"appops" -> "android.app.AppOpsManager", // added in Android 19 -- tends to cause compilation issues because it's so new
    "audio" -> "android.media.AudioManager",
    "connection" -> "android.net.ConnectivityManager",
    "download" -> "android.app.DownloadManager",
    "input_method" -> "android.view.inputmethod.InputMethodManager",
    "keyguard" -> "android.app.KeyguardManager",    
    "layout_inflater" -> "android.view.LayoutInflater",
    "location" -> "android.location.LocationManager",
    "notification" -> "android.app.NotificationManager",
    "power" -> "android.os.PowerManager",
    "search" -> "android.app.SearchManager",
    "uimode" -> "android.app.UiModeManager", 
    "vibrator" -> "android.os.Vibrator",
    "wifi" -> "android.net.wifi.WifiManager",
    "window" -> "android.view.WindowManager"
  )  
  
  type Expression = String
  type Statement = String
  
  override def generateStubs(stubMap : StubMap, generatedStubs : List[File]) : (StubMap, List[File]) = {
    val GET_SYSTEM_SERVICE = "getSystemService"
      
    val stubDir = new File(STUB_DIR)
    if (!stubDir.exists()) stubDir.mkdir()
    
    val inhabitor = new TypeInhabitor  
    val (inhabitantMap, allocs) = SYSTEM_SERVICES_MAP.foldLeft (Map.empty[String,Expression], List.empty[Statement]) ((pair, entry) => {
      val typ = TypeReference.findOrCreate(ClassLoaderReference.Primordial, ClassUtil.walaifyClassName(entry._2))
      try {
        val (inhabitant, allocs) = inhabitor.inhabit(typ, cha, pair._2, doAllocAndReturnVar = false)
        (pair._1 + (entry._1 -> inhabitant), allocs)
      } catch { 
        // we do this in order to be robust in the face of system services that are added in later versions of Android
        // if we use an earlier JAR, lookups of these classes in the class hierarchy will fail and cause an exception
        // catching the exception allows us to pick up the pieces and move on
        case e : Throwable => pair
      }
    })
        

    val strWriter = new StringWriter
    val writer = new JavaWriter(strWriter)
     
    writer.emitPackage(STUB_DIR) 

    val allServices = SYSTEM_SERVICES_MAP.values
    allServices.foreach(typ => writer.emitImports(typ))      
    writer.emitEmptyLine()
    
    writer.beginType(SYSTEM_SERVICE_STUB_CLASS, "class", EnumSet.of(PUBLIC, FINAL)) // begin class
    SYSTEM_SERVICES_MAP.foreach(entry => writer.emitField(entry._2, entry._1, EnumSet.of(PRIVATE, STATIC)))
    writer.emitEmptyLine()
    
    writer.beginInitializer(true) // begin static
    writer.beginControlFlow("try") // begin try    
    // emit initialization of static fields
    // first, emit allocs that we built up in inhabiting the values
    allocs.reverse.foreach(alloc => writer.emitStatement(alloc))
    // next emit the initialization of each field
    inhabitantMap.foreach(entry => writer.emitStatement(s"${entry._1} = ${entry._2}"))
    
    writer.endControlFlow() // end try
    writer.beginControlFlow("catch (Exception e)") // begin catch   
    writer.endControlFlow() // end catch
    writer.endInitializer() // end static          
    writer.emitEmptyLine()
    
    // emit stub for Context.getSystemService(String)
    val paramName = "name"
    writer.beginMethod("Object", GET_SYSTEM_SERVICE, EnumSet.of(PUBLIC, STATIC), "String", paramName)
    writer.beginControlFlow(s"switch ($paramName)") // begin switch
    inhabitantMap.keys.foreach(key => writer.emitStatement("case \"" + key + "\": return " + key))
    writer.emitStatement("default: return null")
    writer.endControlFlow() // end switch
    writer.endMethod()
    writer.endType() // end class
    
    // write out stub to file
    val stubPath = s"$STUB_DIR${File.separator}$SYSTEM_SERVICE_STUB_CLASS"
    val fileWriter = new FileWriter(s"${stubPath}.java")
    if (DEBUG) println(s"Generated stub: ${strWriter.toString()}")
    fileWriter.write(strWriter.toString())    
    // cleanup
    strWriter.close()
    writer.close()    
    fileWriter.close()
    
    // compile stub against Android library 
    val compilerOptions = List("-cp", s"${androidJarPath}")
    if (DEBUG) println(s"Running javac ${compilerOptions(0)} ${compilerOptions(1)}")
    val compiled = JavaUtil.compile(List(stubPath), compilerOptions)
    assert(compiled, s"Couldn't compile stub file $stubPath")   
    
    val getSystemServiceDescriptor = "(Ljava/lang/String;)Ljava/lang/Object;"
    val contextTypeRef = ClassUtil.makeTypeRef(AndroidConstants.CONTEXT_TYPE)
    val getSystemServiceRef = MethodReference.findOrCreate(contextTypeRef, GET_SYSTEM_SERVICE, getSystemServiceDescriptor)
    val getSystemService = cha.resolveMethod(getSystemServiceRef)
    assert(getSystemService != null, "Couldn't find getSystemService() method")
    
    // get all the library methods that a call to getSystemService() might dispatch to (ignoring covariance for simplicity's sake)
    val possibleOverrides = cha.computeSubClasses(contextTypeRef).foldLeft (List(getSystemService)) ((l, c) =>
      if (!ClassUtil.isLibrary(c)) l
      else c.getDeclaredMethods().foldLeft (l) ((l, m) =>
        if (m.getName().toString() == GET_SYSTEM_SERVICE && m.getDescriptor().toString() == getSystemServiceDescriptor) m :: l
        else l
      )
    )     
    
    val stubTypeRef = TypeReference.findOrCreate(ClassLoaderReference.Application, s"L$STUB_DIR/$SYSTEM_SERVICE_STUB_CLASS")
    val shrikePatch = new Patch() {
      override def emitTo(o : Output) : Unit = {
        if (DEBUG) println("Instrumenting call to system service stub")
        // the stack is [String argument to getSystemService, receiver of getSystemService]. we want to get rid of the receiver,
        // but keep the string argument. we do this by performing a swap, then a pop to get rid of the receiver
        o.emit(SwapInstruction.make()) // swap the String argument and receiver on the stack     
        o.emit(PopInstruction.make(1)) // pop the receiver of getSystemService off the stack
        val methodClass = ClassUtil.typeRefToBytecodeType(stubTypeRef)
        o.emit(InvokeInstruction.make(getSystemServiceDescriptor, 
               methodClass,
               GET_SYSTEM_SERVICE, 
               IInvokeInstruction.Dispatch.STATIC)
        )
      }
    }
    
    def tryCreatePatch(i : SSAInvokeInstruction, ir : IR) : Option[Patch] = Some(shrikePatch)
    
    (possibleOverrides.foldLeft (stubMap) ((map, method) => map + (method -> tryCreatePatch)),
     new File(stubPath) :: generatedStubs)
  }   
  
}