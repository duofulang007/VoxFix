// K60 debug-only fault injection; never reads or writes editor text.
import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.request.EventRequest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.net.URI;
import java.net.http.*;

public class ImeVisibilityProbe {
    static Value field(ObjectReference object, String name) {
        return object.getValue(Objects.requireNonNull(object.referenceType().fieldByName(name), name));
    }
    static Value invoke(ObjectReference object, ThreadReference thread, String name, String signature, List<Value> args) throws Exception {
        return object.invokeMethod(thread, object.referenceType().methodsByName(name, signature).get(0), args, ObjectReference.INVOKE_SINGLE_THREADED);
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=2 || !List.of("hidden","repair","recreate").contains(args[0])) throw new IllegalArgumentException("mode and local WebView socket required");
        URI uri=URI.create(args[1]);
        if(!uri.getScheme().equals("ws") || !uri.getHost().equals("127.0.0.1")) throw new IllegalArgumentException("Local debug socket only");
        AttachingConnector connector=Bootstrap.virtualMachineManager().attachingConnectors().stream().filter(c->c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        var options=connector.defaultArguments();
        options.get("hostname").setValue("127.0.0.1"); options.get("port").setValue("19227");
        VirtualMachine vm=connector.attach(options);
        try {
            var activity=vm.classesByName("android.app.ActivityThread").get(0);
            var current=(ObjectReference)activity.getValue(activity.fieldByName("sCurrentActivityThread"));
            var values=((ArrayReference)field((ObjectReference)field(current,"mServices"),"mArray")).getValues();
            var service=(ObjectReference)values.stream().filter(v->v instanceof ObjectReference && ((ObjectReference)v).referenceType().name().equals("local.voxfix.debug.VoxFixImeService")).findFirst().orElseThrow();
            var info=(ObjectReference)field(service,"mInputEditorInfo");
            if(!((StringReference)field(info,"packageName")).value().equals("local.voxfix.debug") || ((BooleanValue)field(service,"busy")).value()) throw new IllegalStateException("Use the idle own-app test editor");
            var web=(ObjectReference)field(service,"web");
            if(!web.equals(field(service,"mInputView")) || ((IntegerValue)field((ObjectReference)field(web,"mAttachInfo"),"mWindowVisibility")).value()!=0) throw new IllegalStateException("Shown input view required");
            var main=vm.allThreads().stream().filter(t->t.name().equals("main")).findFirst().orElseThrow();
            var ws=HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(uri,new WebSocket.Listener(){}).get(5,TimeUnit.SECONDS);
            var request=vm.eventRequestManager().createMethodEntryRequest();
            request.addClassFilter("android.os.Handler"); request.addThreadFilter(main);
            request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD); request.enable();
            // The read-only cursor query posts a main Handler callback without rendering.
            ws.sendText("{\"id\":1,\"method\":\"Runtime.evaluate\",\"params\":{\"expression\":\"VoxFixIme.cursorPosition()\"}}",true).get(5,TimeUnit.SECONDS);
            var event=vm.eventQueue().remove(5000);
            while(event!=null && event.stream().noneMatch(e->e instanceof com.sun.jdi.event.MethodEntryEvent)) {event.resume();event=vm.eventQueue().remove(5000);}
            vm.eventRequestManager().deleteEventRequest(request);
            if(event==null)throw new IllegalStateException("No main Handler event");
            try {
                if(!((BooleanValue)invoke(web,main,"isShown","()Z",List.of())).value())throw new IllegalStateException("Keyboard must be shown");
                if(args[0].equals("recreate")) {
                    var replacement=invoke(service,main,"onCreateInputView","()Landroid/view/View;",List.of());
                    invoke(service,main,"setInputView","(Landroid/view/View;)V",List.of(replacement));
                } else invoke(web,main,"dispatchWindowVisibilityChanged","(I)V",List.of(vm.mirrorOf(args[0].equals("hidden")?4:0)));
                System.out.println("NATIVE_PROBE_PASS="+args[0]);
            } finally {event.resume();ws.abort();}
        } finally {vm.dispose();}
    }
}
