package local.voxfix.screentest;

import android.accessibilityservice.AccessibilityService;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

/** Captures this APK's fixed receiver page, inserts it, then deletes only the new fixture. */
final class ScreenshotUiFlow {
    private static final String HANDLE="截图工具，向内滑动展开，长按拖动";
    private final UiAutomation ui;
    private String trace="";
    private int keyboardOffset;
    private int screenBottom;
    private ScreenshotUiFlow(UiAutomation ui) { this.ui=ui; }
    static String run(Instrumentation test) throws Exception {
        var automation=test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        var info=automation.getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.setServiceInfo(info);
        var flow=new ScreenshotUiFlow(automation);
        try {
            try(var pipe=new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("am start -W -n local.voxfix.screentest/.ScreenshotChatActivity"))) { pipe.readAllBytes(); }
            flow.await("截图聊天测试框",false);
            for(var window:automation.getWindows()) if(window.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD) { automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);SystemClock.sleep(300);break; }
            flow.click(HANDLE);flow.click("截图");flow.await("取消截图",true);
            if(flow.find("截图剪贴板",true)!=null)throw new AssertionError("Arc must have only capture action");
            var bounds=test.getTargetContext().getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
            flow.screenBottom=bounds.bottom;
            long down=SystemClock.uptimeMillis();
            for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP}) {
                float x=bounds.width()*(action==MotionEvent.ACTION_DOWN?.10f:.80f),y=bounds.height()*(action==MotionEvent.ACTION_DOWN?.06f:.14f);
                MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                try { if(!automation.injectInputEvent(event,true))throw new AssertionError("Fixture selection touch refused"); } finally { event.recycle(); }
            }
            flow.await("保存到截图剪贴板",true);SystemClock.sleep(250);
            if(flow.find("取消截图",true)==null)throw new AssertionError("Selection must stay open until an explicit action");
            flow.click("保存到截图剪贴板");flow.await(HANDLE,true);
            flow.click("截图聊天测试框");flow.click("剪贴板");flow.click("图片");
            AccessibilityNodeInfo shot=null;long until=SystemClock.uptimeMillis()+10000;
            String fixtureLabel="查看图片 "+Math.round(bounds.width()*.70f)+"×"+Math.round(bounds.height()*.08f);
            while(shot==null && SystemClock.uptimeMillis()<until) { shot=flow.find(fixtureLabel,true);if(shot==null)SystemClock.sleep(100); }
            if(shot==null)throw new AssertionError("Saved screenshot missing from clipboard");
            flow.tap(shot);
            flow.click("插入聊天");flow.await("已收到截图PNG，原聊天页面保留",false);
            flow.click("删除");flow.await("删除这张图片？",false);flow.click("确定删除");
            flow.await("图片剪贴板",false);
            automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            return "SCREENSHOT_SINGLE_ARC_CAPTURE_IME_IMAGE_PREVIEW_URI_INSERT_HOST_RETAINED_DELETE_OWN_FIXTURE_PASS";
        } finally {
            var cancel=flow.find("取消截图",true);if(cancel!=null)cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
    }
    private AccessibilityNodeInfo findPrefix(String prefix) {
        for(var window:ui.getWindows()) { var root=window.getRoot();if(root!=null && "local.voxfix.debug".equals(String.valueOf(root.getPackageName()))) { var found=match(root,prefix,true,true);if(found!=null)return found; } }
        return null;
    }
    private AccessibilityNodeInfo find(String label,boolean clickable) {
        for(var window:ui.getWindows()) { var root=window.getRoot();if(root!=null && ("local.voxfix.debug".equals(String.valueOf(root.getPackageName())) || "local.voxfix.screentest".equals(String.valueOf(root.getPackageName())))) { var found=match(root,label,clickable,false);if(found!=null)return found; } }
        return null;
    }
    private AccessibilityNodeInfo match(AccessibilityNodeInfo node,String label,boolean clickable,boolean prefix) {
        String text=String.valueOf(node.getText()),description=String.valueOf(node.getContentDescription());
        boolean visible=node.isVisibleToUser() || "local.voxfix.debug".equals(String.valueOf(node.getPackageName()));
        if(visible && (!clickable || node.isEnabled() && node.isClickable()) && (prefix?(text.startsWith(label)||description.startsWith(label)):(label.equals(text)||label.equals(description))))return node;
        for(int i=0;i<node.getChildCount();i++) { var child=node.getChild(i);if(child!=null) { var found=match(child,label,clickable,prefix);if(found!=null)return found; } }
        return null;
    }
    private AccessibilityNodeInfo await(String label,boolean clickable) {
        long until=SystemClock.uptimeMillis()+12000;
        while(SystemClock.uptimeMillis()<until) { var node=find(label,clickable);if(node!=null)return node;SystemClock.sleep(100); }
        throw new AssertionError("Missing screenshot control: "+label+"; touches="+trace);
    }
    private void click(String label) {
        var node=await(label,true);
        if(label.equals(HANDLE)||label.equals("截图")||label.equals("保存到截图剪贴板")) {
            if(!node.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("Native action refused: "+label);
            SystemClock.sleep(150);
        } else tap(node);
    }
    private void tap(AccessibilityNodeInfo node) {
        var bounds=new android.graphics.Rect();node.getBoundsInScreen(bounds);long down=SystemClock.uptimeMillis();
        if("local.voxfix.debug".equals(String.valueOf(node.getPackageName()))) {
            // K60's clipped fixed WebView exposes virtual coordinates below the IME window.
            if(keyboardOffset==0 && "剪贴板".contentEquals(node.getContentDescription())) for(var window:ui.getWindows()) {
                if(window.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    var frame=new android.graphics.Rect();window.getBoundsInScreen(frame);
                    keyboardOffset=Math.min(0,screenBottom-frame.bottom);
                    trace+="IME="+frame+" offset="+keyboardOffset+";";
                }
            }
            bounds.offset(0,keyboardOffset);
        }
        trace+=node.getText()+"/"+node.getContentDescription()+"/"+bounds+";";
        try(var pipe=new ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("input tap "+bounds.centerX()+" "+bounds.centerY()))) { pipe.readAllBytes(); }
        catch(java.io.IOException error){throw new AssertionError("Touch failed",error);}
        SystemClock.sleep(150);
    }
}
