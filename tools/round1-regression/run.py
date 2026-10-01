#!/usr/bin/env python3
"""Run actual non-UI Kotlin helpers on a JVM. Android/Compose/bridge infrastructure is stubbed.
Requires JAVA_HOME, KOTLIN_HOME and JSON_JAR. Does NOT claim device/render/lifecycle coverage.
"""
import os,re,subprocess,tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[2]
source=root/'android/app/src/main/java/com/codespace/ide'
with tempfile.TemporaryDirectory(prefix='round1-tests-') as tmp:
 d=Path(tmp)
 stubs={
 'Context.kt':'''package android.content
import java.io.File
class Context(val filesDir: File, val packageName: String = "com.codespace.ide")
''',
 'Compose.kt':'''package androidx.compose.runtime
import kotlin.reflect.KProperty
annotation class Composable
interface MutableState<T> { var value:T; operator fun component1():T; operator fun component2():(T)->Unit }
fun <T> mutableStateOf(v:T):MutableState<T> = object:MutableState<T> {
 override var value=v; override fun component1()=value; override fun component2():(T)->Unit={value=it}
}
fun <T> rememberUpdatedState(v:T)=mutableStateOf(v)
fun <K,V> mutableStateMapOf():MutableMap<K,V> = linkedMapOf()
fun <T> remember(vararg keys:Any?,calculation:()->T):T = calculation()
operator fun <T> MutableState<T>.getValue(a:Any?,p:KProperty<*>)=value
operator fun <T> MutableState<T>.setValue(a:Any?,p:KProperty<*>,v:T){value=v}
@Composable fun LaunchedEffect(vararg keys:Any?,block:suspend ()->Unit) {}
''',
 'Coroutine.kt':'''package kotlinx.coroutines
suspend fun delay(millis:Long) {}
object Dispatchers { val Default=Any() }
suspend fun <T> withContext(context:Any,block:suspend ()->T):T=block()
''',
 'Mcp.kt':'''package com.codespace.ide.agent
import android.content.Context
import org.json.JSONObject
object McpClientManager { fun nativeToolDefinitions(c:Context)=listOf(JSONObject("""{"type":"function","function":{"name":"mcp_demo_ping","description":"ping","parameters":{"type":"object","properties":{}}}}""")) }
''',
 'Trust.kt':'''package com.codespace.ide.security
import android.content.Context
object TrustState { var root:String?=null; fun activeProjectRoot(c:Context)=root }
''',
 'Proot.kt':'''package com.codespace.ide.terminal
import android.content.Context
import java.io.File
object ProotInstaller {
 fun guestToHostPath(c:Context,p:String)=when {
  p.startsWith("/host-files/projects/")->File(c.filesDir,"projects/"+p.removePrefix("/host-files/projects/"))
  else->File(c.filesDir,"rootfs/"+p.removePrefix("/"))
 }
}
''',
 'Buffers.kt':'''package com.codespace.ide.editor
object EditorBufferStore { val buffers=mutableMapOf<String,String>(); fun contentOf(p:String)=buffers[p] }
object FileCache { fun invalidate(p:String) {} }
''',
 'History.kt':'''package com.codespace.ide.util
import java.io.File
object VersionHistoryV2 { fun v2DirFor(r:File,p:String):File?=null; fun trimGrouped(f:File) {} }
'''
 }
 description=re.search(r'const val TOOLS_DESCRIPTION = """(.*?)"""',(source/'agent/AgentTools.kt').read_text(),re.S).group(1)
 agent_source=(source/'agent/AgentTools.kt').read_text()
 forwarding=re.search(r'    private fun resolveToolPath\(.*?\n        ToolPathResolver.resolve\(context, path\)',agent_source,re.S).group(0).replace('private fun','fun',1)
 calls=re.findall(r'resolveToolPath\((context, [^)]+)\)',agent_source)
 assert len(calls)==4, 'Expected all four file-tool wrappers to route through shared resolver'
 stubs['Forwarding.kt']='package com.codespace.ide.agent\nimport android.content.Context\n'+forwarding+'\nfun compileActualFileToolCalls(context:Context,path:String,trimmed:String) {\n'+';\n'.join('resolveToolPath('+c+')' for c in calls)+'\n}\n'
 write_body=agent_source.split('    private fun writeFile(',1)[1].split('    private fun listFiles(',1)[0]
 assert 'guestToHostPath' not in write_body, 'Direct write must not remap the shared resolver result'
 stubs['Tools.kt']='package com.codespace.ide.agent\nobject AgentTools { const val TOOLS_DESCRIPTION = """'+description+'""" }\n'
 for name,text in stubs.items():(d/name).write_text(text)
 (d/'Main.kt').write_text(r'''import android.content.Context
import com.codespace.ide.chat.*
import com.codespace.ide.editor.*
import com.codespace.ide.agent.ToolPathResolver
import org.json.*
import java.io.File
var checks=0
fun expect(value:Boolean,label:String){check(value){label};checks++}
fun main(){
 val root=kotlin.io.path.createTempDirectory("round1-files").toFile()
 try {
 val context=Context(File(root,"app-files").also{it.mkdirs()})
 val project=File(context.filesDir,"projects/demo").also{it.mkdirs()}
 val native=JSONObject("""{"content":null,"tool_calls":[{"id":"abc","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"Main.kt\"}"}},{"id":"def","type":"function","function":{"name":"run_command","arguments":"{\"command\":\"echo ok\"}"}}]}""")
 val decoded=NativeToolProtocol.responseText(native)
 val calls=NativeToolProtocol.calls(decoded)
 expect(calls.length()==2,"native two calls")
 expect(calls.getJSONObject(0).getString("id")=="abc","native id preserved")
 expect(NativeToolProtocol.resultMessage(calls,1,"OK").getString("tool_call_id")=="def","result paired")
 expect(NativeToolProtocol.assistantMessage(decoded,calls).getJSONArray("tool_calls").length()==2,"assistant structured history")
 val qwen="<tool_call>\n{\"name\":\"read_file\",\"arguments\":{\"path\":\"Main.kt\"}}\n</tool_call>".replace("\\n","\n")
 expect(NativeToolProtocol.calls(qwen).length()==1,"Qwen multiline tag")
 expect(NativeToolProtocol.calls("<tool>{\"name\":\"read_file\",\"arguments\":{\"path\":\"Main.kt\"}}</tool>").length()==1,"legacy tag")
 expect(NativeToolProtocol.calls("Installed nano successfully").length()==0,"text is no call")
 val stream=NativeToolProtocol.StreamCalls()
 stream.accept(JSONObject("""{"tool_calls":[{"index":0,"id":"stream0","function":{"name":"read_file","arguments":"{\"pa"}}]}"""))
 stream.accept(JSONObject("""{"tool_calls":[{"index":0,"function":{"arguments":"th\":\"Main.kt\"}"}}]}"""))
 val streamCalls=NativeToolProtocol.calls(stream.response(""))
 expect(streamCalls.getJSONObject(0).getString("id")=="stream0","stream id")
 expect(JSONObject(streamCalls.getJSONObject(0).getJSONObject("function").getString("arguments")).getString("path")=="Main.kt","stream arguments assembled")
 expect(NativeToolProtocol.schemas(context,listOf("read_file")).length()==1,"native custom allowlist")
 val schemas=NativeToolProtocol.schemas(context,null)
 expect(schemas.length()==33,"32 builtins plus enabled MCP schema")
 val evidence=ToolExecutionEvidence()
 expect(evidence.finish("Installed nano").contains("NOT EXECUTED"),"unbacked prose labeled")
 expect(evidence.record("write_file",true,true,"staged").contains("STAGED ONLY"),"staged is not written")
 expect(evidence.record("run_command",false,false,"Skipped").contains("NOT EXECUTED"),"denied is not executed")
 expect(evidence.record("run_command",true,false,"Exit code 1\nfail").contains("FAILED"),"failure not success")
 expect(evidence.finish("Done").contains("Unverified model summary"),"summary not arbitrary success receipt")
 expect(ToolPathResolver.resolve(context,"/storage/ABCD-1234/new.kt")=="/storage/ABCD-1234/new.kt","new removable-storage host path preserved")
 expect(ToolPathResolver.resolve(context,"nested/new.kt",project.path)==File(project,"nested/new.kt").canonicalPath,"relative project resolution")
 expect(ToolPathResolver.resolve(context,"/root/nested/new.kt",project.path)==File(context.filesDir,"rootfs/root/nested/new.kt").canonicalPath,"new guest path translation")
 expect(ToolPathResolver.resolve(context,File(project,"missing/new.kt").path)==File(project,"missing/new.kt").canonicalPath,"new Android path preserved")
 var failed=false;try{ToolPathResolver.resolve(context,"relative.kt",null)}catch(e:IllegalArgumentException){failed=true}
 expect(failed,"missing relative root refused")
 PendingChangesStore.activeSessionId="tests";PendingChangesStore.activeProjectRoot=project.path
 val existing=File(project,"Main.kt").also{it.writeText("before")}
 PendingChangesStore.stage("Main.kt","after",context,project.path)
 expect(PendingChangesStore.hasPending(existing.canonicalPath),"stage canonical host key")
 expect(existing.readText()=="before","stage no disk write")
 expect(PendingChangesStore.apply(existing.canonicalPath) is PendingChangesStore.ApplyOutcome.Applied,"existing apply")
 expect(existing.readText()=="after","existing disk content")
 val newFile=File(project,"nested/new.kt")
 PendingChangesStore.stage("nested/new.kt","brand new",context,project.path)
 expect(PendingChangesStore.pendingFor("tests").single{it.path==newFile.canonicalPath}.isNewFile,"new file identity")
 expect(PendingChangesStore.apply(newFile.canonicalPath) is PendingChangesStore.ApplyOutcome.Applied,"new apply without read")
 expect(newFile.readText()=="brand new","new nested content written")
 val empty=File(project,"empty.kt").also{it.writeText("")}
 PendingChangesStore.stage(empty.path,"not empty",context,project.path)
 expect(!PendingChangesStore.pendingFor("tests").single{it.path==empty.canonicalPath}.isNewFile,"empty existing is not new")
 val appeared=File(project,"appeared.kt")
 PendingChangesStore.stage(appeared.path,"proposal",context,project.path);appeared.writeText("other writer")
 expect(PendingChangesStore.apply(appeared.path) is PendingChangesStore.ApplyOutcome.Drift,"new file appeared drift")
 expect(appeared.readText()=="other writer","no drift overwrite")
 expect(PendingChangesStore.forceApply(appeared.path) is PendingChangesStore.ApplyOutcome.Applied,"explicit force apply")
 expect(appeared.readText()=="proposal","force content")
 PendingChangesStore.stage(existing.path,"more",context,project.path);existing.delete()
 expect(PendingChangesStore.apply(existing.path) is PendingChangesStore.ApplyOutcome.Blocked,"deleted existing fails closed")
 val dir=File(project,"unreadable-directory").also{it.mkdirs()}
 PendingChangesStore.stage(dir.path,"bad",context,project.path)
 expect(PendingChangesStore.apply(dir.path) is PendingChangesStore.ApplyOutcome.Blocked,"directory cannot masquerade as new file")
 var current=File(project,"a/Main.kt").canonicalPath
 val a=current;val b=File(project,"b/Main.kt").canonicalPath
 val jump=FileOwnedJumpState{current};jump.value=40;val first=jump.pending!!
 expect(jump.lineFor(a)==40,"own jump displayed")
 current=b;expect(jump.lineFor(b)==0,"same-name switch no inherited jump before effect")
 jump.clearOtherFile(b);expect(jump.pending==null,"model switch clears transient")
 jump.value=60;val second=jump.pending!!;jump.clearIfCurrent(first)
 expect(jump.pending==second,"stale timer cannot clear newer jump")
 jump.value=60;expect(jump.pending!!.serial!=second.serial,"same line has fresh request id")
 val external=PendingEditorJump(a,90,999);jump.acceptExternal(external);jump.clearOtherFile(b);jump.acceptExternal(external)
 expect(jump.pending==null,"consumed external not replayed on return")
 val ea=LintError(0,3,"A diagnostic");val eb=LintError(4,6,"B diagnostic")
 PerFileStateStore.setSquiggles(a,listOf(ea))
 expect(PerFileStateStore.stateFor(b).squiggles.isEmpty(),"same-name B never reads A markers")
 expect(PerFileStateStore.stateFor(File(project,"a/../a/Main.kt").path).squiggles==listOf(ea),"canonical alias reads same markers")
 PerFileStateStore.setSquiggles(b,listOf(eb))
 expect(PerFileStateStore.stateFor(a).squiggles==listOf(ea),"publishing B preserves A")
 PerFileStateStore.setBookmarks(a,setOf(2));PerFileStateStore.setSquiggles(a,emptyList())
 expect(PerFileStateStore.stateFor(a).bookmarks==setOf(2),"marker refresh preserves bookmarks")
 expect(PerFileStateStore.stateFor(b).squiggles==listOf(eb),"clearing A leaves B markers")
 expect(rememberEditorDiagnostics(a,"",com.codespace.ide.domain.Language.KOTLIN,PerFileStateStore.stateFor(a).squiggles).isEmpty(),"render merge reads empty A synchronously")
 expect(rememberEditorDiagnostics(b,"",com.codespace.ide.domain.Language.KOTLIN,PerFileStateStore.stateFor(b).squiggles)==listOf(eb),"render merge reads only B synchronously")
 val renamed=File(project,"b/Renamed.kt").path;PerFileStateStore.rekey(b,renamed)
 expect(PerFileStateStore.stateFor(renamed).squiggles==listOf(eb),"rename preserves file markers")
 expect(PerFileStateStore.stateFor(b).squiggles.isEmpty(),"rename removes old-key markers")
 println("PASS: $checks executable assertions against actual Kotlin helpers. Android/Compose/proot bridge infrastructure stubbed; device render tests still required.")
 } finally {root.deleteRecursively()}
}
''')
 kotlin=Path(os.environ['KOTLIN_HOME'])/'bin/kotlinc'
 actual=[source/p for p in ['chat/NativeToolProtocol.kt','chat/ToolExecutionEvidence.kt','chat/PendingChangesStore.kt','agent/ToolPathResolver.kt','util/CanonicalPaths.kt','editor/FileOwnedJumpState.kt','editor/PerFileStateStore.kt','editor/EditorDiagnosticState.kt','editor/LintAnalyzer.kt','domain/Language.kt']]
 jar=d/'tests.jar'
 subprocess.run([str(kotlin),*[str(p) for p in actual],*[str(p) for p in d.glob('*.kt')],'-cp',os.environ['JSON_JAR'],'-include-runtime','-d',str(jar)],check=True)
 subprocess.run([str(Path(os.environ['JAVA_HOME'])/'bin/java'),'-cp',str(jar)+':'+os.environ['JSON_JAR'],'MainKt'],check=True)
