package local.voxfix.screentest;

import android.app.Activity;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputContentInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Owned chat receiver: verifies actual URI grants without contacting anyone. */
public class ScreenshotChatActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        var root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,160,24,24);
        var status=new TextView(this);status.setText("截图图片接收测试");root.addView(status);
        var editor=new EditText(this) {
            @Override public InputConnection onCreateInputConnection(EditorInfo info) {
                InputConnection base=super.onCreateInputConnection(info);info.contentMimeTypes=new String[]{"image/png"};
                return new InputConnectionWrapper(base,false) {
                    @Override public boolean commitContent(InputContentInfo content,int flags,Bundle options) {
                        try {
                            content.requestPermission();
                            try(var input=getContentResolver().openInputStream(content.getContentUri())) {
                                byte[] signature=input.readNBytes(8);
                                if(signature.length!=8 || signature[0]!=(byte)137 || signature[1]!=80) return false;
                            } finally { content.releasePermission(); }
                            status.setText("已收到截图PNG，原聊天页面保留");return true;
                        }catch(Exception error){status.setText("接收失败："+error);return false;}
                    }
                };
            }
        };
        editor.setHint("测试输入框");editor.setContentDescription("截图聊天测试框");root.addView(editor);setContentView(root);
    }
}
