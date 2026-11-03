package local.voxfix.screentest;

import android.app.Instrumentation;
import android.content.ContentProvider;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ProviderInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.View;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/** Owned fixtures only: no screenshot of user apps, clipboard mutation or user-data reset. */
final class ScreenshotFoundationChecks {
    static String run(Instrumentation test) throws Exception {
        Context context = test.getTargetContext();
        ClassLoader loader = context.createPackageContext("local.voxfix.debug",Context.CONTEXT_INCLUDE_CODE|Context.CONTEXT_IGNORE_SECURITY).getClassLoader();
        Class<?> type = loader.loadClass("local.voxfix.debug.ScreenshotStore");
        File owned = new File(context.getFilesDir(),"screenshot-foundation-test");
        check(owned.isDirectory() || owned.mkdirs(),"fixture directory");
        Context isolated = new ContextWrapper(context) { @Override public File getFilesDir() { return owned; } };
        Object store = type.getConstructor(Context.class).newInstance(isolated);
        Bitmap image = Bitmap.createBitmap(240,160,Bitmap.Config.ARGB_8888); image.eraseColor(Color.WHITE);
        try {
            check(((List<?>)type.getMethod("entries").invoke(store)).isEmpty(),"fixture starts empty");
            Class<?> sessionType=loader.loadClass("local.voxfix.debug.ScreenshotSession");
            Bitmap original=Bitmap.createBitmap(80,60,Bitmap.Config.ARGB_8888);
            Object session=sessionType.getConstructor(Bitmap.class).newInstance(original);
            Bitmap crop=(Bitmap)sessionType.getMethod("crop",Rect.class).invoke(session,new Rect(0,0,80,60));
            sessionType.getMethod("updateText",String.class).invoke(session,"未保存的识别、翻译或编辑文字");
            check(crop!=original && ((List<?>)type.getMethod("entries").invoke(store)).isEmpty(),"text operations do not require or save a screenshot");
            sessionType.getMethod("close").invoke(session);
            check(original.isRecycled() && !crop.isRecycled(),"pending async work owns its crop after session close");
            check(sessionType.getMethod("getText").invoke(session).equals(""),"session text released without history");
            crop.recycle();
            Object entry = type.getMethod("save",Bitmap.class).invoke(store,image);
            String id = (String) entry.getClass().getMethod("getId").invoke(entry);
            check(((List<?>)type.getMethod("entries").invoke(store)).size()==1,"saved entry");
            File file = (File)type.getMethod("file",String.class).invoke(store,id);
            File thumb = (File)type.getMethod("thumbnail",String.class).invoke(store,id);
            check(file.isFile() && thumb.isFile(),"PNG and bounded thumbnail");
            Class<?> keyboardType=loader.loadClass("local.voxfix.debug.ScreenshotKeyboard");
            Object keyboard=keyboardType.getConstructor(Context.class).newInstance(isolated);
            var route=keyboardType.getMethod("route",Uri.class);
            check(route.invoke(keyboard,Uri.parse("https://example.com/screenshots/"+id+"/image"))==null,"remote origin never serves private images");
            for(String kind:new String[]{"image","thumb"}) {
                var response=(android.webkit.WebResourceResponse)route.invoke(keyboard,Uri.parse("https://voxfix.local/screenshots/"+id+"/"+kind));
                check(response.getStatusCode()==200 && "no-store".equals(response.getResponseHeaders().get("Cache-Control")),"private keyboard image route");
                try(var data=response.getData()){check(data.readAllBytes().length>0,"keyboard image payload");}
            }
            var invalidRoute=(android.webkit.WebResourceResponse)route.invoke(keyboard,Uri.parse("https://voxfix.local/screenshots/bad/image"));
            check(invalidRoute.getStatusCode()==404,"keyboard route rejects invalid ID");
            var croppedList=(org.json.JSONObject)keyboardType.getMethod("crop",String.class,int.class,int.class,int.class,int.class).invoke(keyboard,id,10,20,110,100);
            check(croppedList.getJSONArray("items").length()==2 && file.isFile(),"crop saves a copy and keeps original");
            String croppedId=croppedList.getJSONArray("items").getJSONObject(0).getString("id");
            Bitmap croppedImage=android.graphics.BitmapFactory.decodeFile(((File)type.getMethod("file",String.class).invoke(store,croppedId)).getPath());
            check(croppedImage.getWidth()==100 && croppedImage.getHeight()==80,"crop pixel dimensions");croppedImage.recycle();
            try { keyboardType.getMethod("crop",String.class,int.class,int.class,int.class,int.class).invoke(keyboard,id,-1,0,20,20);throw new AssertionError("out of bounds crop accepted"); }
            catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalArgumentException,"crop boundary validated");}
            type.getMethod("delete",String.class).invoke(store,croppedId);
            Uri uri = (Uri)type.getMethod("uri",String.class).invoke(store,id);
            ContentProvider provider = (ContentProvider)loader.loadClass("local.voxfix.debug.ScreenshotContentProvider").getConstructor().newInstance();
            ProviderInfo info = new ProviderInfo(); info.authority = context.getPackageName()+".screenshots"; provider.attachInfo(isolated,info);
            check(provider.getType(uri).equals("image/png"),"URI MIME");
            try (var descriptor = provider.openFile(uri,"r")) { check(descriptor.getStatSize()==file.length(),"read-only URI payload"); }
            try (var cursor = provider.query(uri,null,null,null,null)) { check(cursor.moveToFirst() && cursor.getLong(cursor.getColumnIndexOrThrow("_size"))==file.length(),"URI size metadata"); }
            try { provider.openFile(uri,"rw"); throw new AssertionError("writable URI accepted"); } catch (IllegalArgumentException expected) { }
            for (String invalid : new String[]{"../outside","bad",id+".png"}) {
                try { type.getMethod("file",String.class).invoke(store,invalid); throw new AssertionError("invalid ID accepted"); }
                catch (InvocationTargetException expected) { check(expected.getCause() instanceof IllegalArgumentException,"ID trust boundary"); }
            }
            File index = new File(owned,"screenshot-clipboard/index.json");
            byte[] before = Files.readAllBytes(index.toPath());
            byte[] future = "{\"version\":999,\"items\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(index.toPath(),future);
            try { type.getMethod("save",Bitmap.class).invoke(store,image); throw new AssertionError("future index overwritten"); }
            catch (InvocationTargetException expected) { check(expected.getCause() instanceof IllegalArgumentException,"future schema rejected"); }
            check(Arrays.equals(future,Files.readAllBytes(index.toPath())) && file.isFile(),"failed save retains existing data");
            Files.write(index.toPath(),before);
            var capacity = new org.json.JSONObject(new String(before,java.nio.charset.StandardCharsets.UTF_8));
            var full = new org.json.JSONArray();
            for(int i=0;i<100;i++) full.put(new org.json.JSONObject(capacity.getJSONArray("items").getJSONObject(0).toString()).put("id",String.format("%032x",i)));
            capacity.put("items",full);
            byte[] fullIndex=capacity.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(index.toPath(),fullIndex);
            try { type.getMethod("save",Bitmap.class).invoke(store,image); throw new AssertionError("capacity limit ignored"); }
            catch(InvocationTargetException expected) { check(expected.getCause() instanceof IllegalStateException,"capacity rejects new image"); }
            check(Arrays.equals(fullIndex,Files.readAllBytes(index.toPath())) && file.isFile(),"capacity never evicts existing screenshots");
            Files.write(index.toPath(),before);
            type.getMethod("delete",String.class).invoke(store,id);
            check(!file.exists() && !thumb.exists() && ((List<?>)type.getMethod("entries").invoke(store)).isEmpty(),"explicit delete removes only selected image");
            check(index.delete(),"owned empty index cleanup");
        } finally { image.recycle(); }
        Throwable[] failure = {null};
        test.runOnMainSync(() -> {
            Bitmap frame = Bitmap.createBitmap(1200,1800,Bitmap.Config.ARGB_8888); frame.eraseColor(Color.WHITE);
            try {
                Object unit = loader.loadClass("kotlin.Unit").getField("INSTANCE").get(null);
                Class<?> f0=loader.loadClass("kotlin.jvm.functions.Function0"), f2=loader.loadClass("kotlin.jvm.functions.Function2");
                int[] saves={0}, exits={0}; boolean[] copied={false}; Rect[] saved={null};
                Object save=Proxy.newProxyInstance(loader,new Class<?>[]{f2},(p,m,a)->{ saves[0]++; saved[0]=new Rect((Rect)a[0]); copied[0]=(Boolean)a[1]; return unit; });
                Object close=Proxy.newProxyInstance(loader,new Class<?>[]{f0},(p,m,a)->{ exits[0]++; return unit; });
                Object noop=Proxy.newProxyInstance(loader,new Class<?>[]{f0},(p,m,a)->unit);
                Class<?> overlayType=loader.loadClass("local.voxfix.debug.ScreenshotCaptureOverlay");
                Object overlay=overlayType.getConstructor(Context.class,Bitmap.class,f2,f0,f0).newInstance(context,frame,save,close,noop);
                View root=(View)field(overlay,"root"); layout(root,1080,1920);
                View selector=(View)field(overlay,"selector");
                check(!((View)field(overlay,"keep")).isEnabled(),"no save without a region");
                gesture(selector,200,300,800,1100);
                Rect first=new Rect((Rect)field(overlay,"picked"));
                check(saves[0]==0 && first.width()>0,"release only selects, never saves or recognizes");
                Bitmap rendered=Bitmap.createBitmap(1080,1920,Bitmap.Config.ARGB_8888);
                try {
                    root.draw(new android.graphics.Canvas(rendered));check((rendered.getPixel(200,600)&0xffffff)==0x4096ff,"Snow Shot blue selection border");
                    try(var output=new java.io.FileOutputStream(new File(context.getFilesDir(),"screenshot-foundation-frame.png"))) { check(rendered.compress(Bitmap.CompressFormat.PNG,100,output),"render evidence"); }
                }
                finally { rendered.recycle(); }
                gesture(selector,500,700,540,750);
                Rect moved=new Rect((Rect)field(overlay,"picked"));
                check(moved.width()==first.width() && moved.height()==first.height() && !moved.equals(first),"move preserves region size");
                gesture(selector,moved.right*1080f/1200,moved.bottom*1920f/1800,moved.right*1080f/1200+50,moved.bottom*1920f/1800+60);
                Rect resized=new Rect((Rect)field(overlay,"picked"));
                check(resized.width()>moved.width() && resized.height()>moved.height(),"corner resize");
                View toolbar=(View)field(overlay,"toolbar");
                check(toolbar.getTranslationX()>=0 && toolbar.getTranslationX()+toolbar.getWidth()<=root.getWidth(),"toolbar stays on screen");
                check(toolbar.getTranslationY()>=0 && toolbar.getTranslationY()+toolbar.getHeight()<=root.getHeight(),"toolbar vertical bounds");
                ((View)field(overlay,"keep")).performClick();
                check(saves[0]==1 && !copied[0] && saved[0].equals(resized),"explicit save uses final adjusted region");
                check(!((View)field(overlay,"copy")).isEnabled(),"save cannot double-submit");
                overlayType.getMethod("failure",String.class).invoke(overlay,"fixture failure");
                ((View)field(overlay,"copy")).performClick(); check(saves[0]==2 && copied[0],"failure preserves region and permits retry/copy");
                overlayType.getMethod("failure",String.class).invoke(overlay,"fixture reset");
                ((View)field(overlay,"full")).performClick(); check(new Rect(0,0,1200,1800).equals(field(overlay,"picked")),"full screenshot selection");
                check(((View)field(overlay,"eye")).getVisibility()==View.GONE,"eye appears only after a result exists");
                var draft=new android.widget.EditText(context);draft.setText("原位编辑中的测试文字");draft.setSelection(2,5);
                overlayType.getMethod("showResultLayer",View.class).invoke(overlay,draft);layout(root,1080,1920);
                ((View)field(overlay,"eye")).performClick();check(((View)field(overlay,"resultLayer")).getVisibility()==View.INVISIBLE,"eye reveals original without removing result");
                ((View)field(overlay,"eye")).performClick();check(((View)field(overlay,"resultLayer")).getVisibility()==View.VISIBLE && draft.getText().toString().equals("原位编辑中的测试文字") && draft.getSelectionStart()==2 && draft.getSelectionEnd()==5,"eye retains draft and selection");
                ((View)field(overlay,"cancel")).performClick(); check(exits[0]==1,"cancel once");
            } catch(Throwable error) { failure[0]=error; }
            finally { frame.recycle(); }
        });
        if(failure[0]!=null) throw new AssertionError("Screenshot controls",failure[0]);
        return "SCREENSHOT_FOUNDATION_PRIVATE_STORE_PNG_THUMB_URI_READONLY_PATH_BOUNDARY_SCHEMA_RECOVERY_DELETE_SELECT_MOVE_RESIZE_EXPLICIT_SAVE_RETRY_COPY_CANCEL_PASS";
    }
    private static Object field(Object object,String name) throws Exception { var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object); }
    private static void layout(View view,int width,int height) { view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height); }
    private static void gesture(View view,float x,float y,float endX,float endY) {
        long now=android.os.SystemClock.uptimeMillis();
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP}) {
            MotionEvent event=MotionEvent.obtain(now,now+20,action,action==MotionEvent.ACTION_DOWN?x:endX,action==MotionEvent.ACTION_DOWN?y:endY,0);
            try{check(view.dispatchTouchEvent(event),"selection touch consumed");}finally{event.recycle();}
        }
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
