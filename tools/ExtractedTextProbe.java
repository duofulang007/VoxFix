// Debug-only regression probe. Only operates on a blank own-app test field and fixed test text.
import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.request.EventRequest;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class ExtractedTextProbe {
    static final String TEXT = "横屏删除测试AB";
    static Value field(ObjectReference o, String n) { return o.getValue(Objects.requireNonNull(o.referenceType().fieldByName(n), n)); }
    static Value call(ObjectReference o, ThreadReference t, String n, String sig, Value... args) throws Exception {
        return o.invokeMethod(t, o.referenceType().methodsByName(n, sig).get(0), List.of(args), ObjectReference.INVOKE_SINGLE_THREADED);
    }
    static String string(Value v, ThreadReference t) throws Exception {
        return v == null ? "" : v instanceof StringReference s ? s.value() : ((StringReference)call((ObjectReference)v,t,"toString","()Ljava/lang/String;")).value();
    }
    public static void main(String[] args) throws Exception {
        if((args.length!=2 && args.length!=3) || !List.of("seed","check","arm","clear").contains(args[0])) throw new IllegalArgumentException("mode, local WebView socket and optional expected length required");
        URI uri=URI.create(args[1]);
        if(!uri.getScheme().equals("ws") || !uri.getHost().equals("127.0.0.1"))throw new IllegalArgumentException("Local debug socket only");
        var connector=Bootstrap.virtualMachineManager().attachingConnectors().stream().filter(c->c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        var opts=connector.defaultArguments();opts.get("hostname").setValue("127.0.0.1");opts.get("port").setValue("19227");
        var vm=connector.attach(opts);
        try{
            var at=vm.classesByName("android.app.ActivityThread").get(0);
            var current=(ObjectReference)at.getValue(at.fieldByName("sCurrentActivityThread"));
            var values=((ArrayReference)field((ObjectReference)field(current,"mServices"),"mArray")).getValues();
            var service=(ObjectReference)values.stream().filter(v->v instanceof ObjectReference o && o.referenceType().name().equals("local.voxfix.debug.VoxFixImeService")).findFirst().orElseThrow();
            var info=(ObjectReference)field(service,"mInputEditorInfo");
            if(!((StringReference)field(info,"packageName")).value().equals("local.voxfix.debug") || ((BooleanValue)field(service,"busy")).value())throw new IllegalStateException("Idle own-app test editor required");
            var main=vm.allThreads().stream().filter(t->t.name().equals("main")).findFirst().orElseThrow();
            var ws=HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(uri,new WebSocket.Listener(){}).get(5,TimeUnit.SECONDS);
            var request=vm.eventRequestManager().createMethodEntryRequest();request.addClassFilter("android.os.Handler");request.addThreadFilter(main);request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);request.enable();
            ws.sendText("{\"id\":1,\"method\":\"Runtime.evaluate\",\"params\":{\"expression\":\"VoxFixIme.cursorPosition()\"}}",true).get(5,TimeUnit.SECONDS);
            var event=vm.eventQueue().remove(5000);
            while(event!=null && event.stream().noneMatch(e->e instanceof com.sun.jdi.event.MethodEntryEvent)){event.resume();event=vm.eventQueue().remove(5000);}
            vm.eventRequestManager().deleteEventRequest(request);if(event==null)throw new IllegalStateException("No main Handler event");
            try{
                var connection=(ObjectReference)call(service,main,"getCurrentInputConnection","()Landroid/view/inputmethod/InputConnection;");
                var type=(ClassType)vm.classesByName("android.view.inputmethod.ExtractedTextRequest").get(0);
                var req=type.newInstance(main,type.methodsByName("<init>","()V").get(0),List.of(),ObjectReference.INVOKE_SINGLE_THREADED);
                var extracted=(ObjectReference)call(connection,main,"getExtractedText","(Landroid/view/inputmethod/ExtractedTextRequest;I)Landroid/view/inputmethod/ExtractedText;",req,vm.mirrorOf(0));
                String host=string(field(extracted,"text"),main);
                if(!TEXT.startsWith(host))throw new IllegalStateException("Non-test content present; aborted without editing");
                var view=(ObjectReference)field(service,"mExtractEditText");
                if(view==null)throw new IllegalStateException("Fullscreen extract view required");
                String visible=string(call(view,main,"getText","()Landroid/text/Editable;"),main);
                if(args[0].equals("seed")){
                    if(!host.isEmpty())throw new IllegalStateException("Blank test field required");
                    call(connection,main,"commitText","(Ljava/lang/CharSequence;I)Z",vm.mirrorOf(TEXT),vm.mirrorOf(1));
                }else if(args[0].equals("arm")){
                    if(!host.equals(TEXT))throw new IllegalStateException("Seed before arming");
                    call(service,main,"armVoiceLearning","(Ljava/lang/String;)V",vm.mirrorOf(TEXT));
                }else if(args[0].equals("clear")){
                    call(service,main,"resetVoiceLearning","()V");
                    call(connection,main,"setSelection","(II)Z",vm.mirrorOf(0),vm.mirrorOf(host.length()));
                    call(connection,main,"commitText","(Ljava/lang/CharSequence;I)Z",vm.mirrorOf(""),vm.mirrorOf(1));
                }else{
                    if(args.length==3 && host.length()!=Integer.parseInt(args[2]))throw new AssertionError("Unexpected host length="+host.length()+", expected="+args[2]);
                    if(!host.equals(visible))throw new AssertionError("Extract view stale: hostLength="+host.length()+" displayLength="+visible.length());
                    System.out.println("EXTRACT_SYNC_PASS length="+host.length());
                }
            }finally{event.resume();ws.abort();}
        }finally{vm.dispose();}
    }
}
