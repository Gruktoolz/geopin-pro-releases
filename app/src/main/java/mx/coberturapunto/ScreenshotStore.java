package mx.coberturapunto;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class ScreenshotStore {
    static Uri save(Context context,Bitmap bitmap) throws IOException {
        if(bitmap.getWidth()*16!=bitmap.getHeight()*9)throw new IOException("La captura no tiene proporción vertical 9:16.");
        String name="Screenshot_"+new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS",Locale.US).format(new Date())+".jpg";
        ContentValues values=new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME,name);
        values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");
        values.put(MediaStore.Images.Media.WIDTH,bitmap.getWidth());
        values.put(MediaStore.Images.Media.HEIGHT,bitmap.getHeight());
        if(Build.VERSION.SDK_INT>=29){
            values.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_DCIM+"/Screenshots");
            values.put(MediaStore.Images.Media.IS_PENDING,1);
        }else{
            java.io.File folder=new java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),"Screenshots");
            if(!folder.isDirectory() && !folder.mkdirs())throw new IOException("No se pudo abrir la carpeta de capturas.");
            values.put(MediaStore.Images.Media.DATA,new java.io.File(folder,name).getAbsolutePath());
        }
        Uri uri=context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
        if(uri==null)throw new IOException("No se pudo guardar la captura.");
        try{
            try(OutputStream out=context.getContentResolver().openOutputStream(uri)){
                if(out==null || !bitmap.compress(Bitmap.CompressFormat.JPEG,95,out))throw new IOException("No se pudo escribir la captura.");
            }
            if(Build.VERSION.SDK_INT>=29){ContentValues ready=new ContentValues();ready.put(MediaStore.Images.Media.IS_PENDING,0);context.getContentResolver().update(uri,ready,null,null);}
            return uri;
        }catch(Exception failure){
            context.getContentResolver().delete(uri,null,null); // Only this unfinished image created by this call.
            if(failure instanceof IOException)throw (IOException)failure;
            throw new IOException("No se pudo guardar la captura.",failure);
        }
    }
}
