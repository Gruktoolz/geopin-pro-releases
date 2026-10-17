package mx.coberturapunto;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

/** Uses exact integer multiples of 9 by 16, with no stretching of the web page. */
final class CaptureFrame extends FrameLayout {
    private boolean wideCapture;
    CaptureFrame(Context context){super(context);}
    void setWideCapture(boolean enabled){wideCapture=enabled;requestLayout();}
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        super.onMeasure(widthSpec,heightSpec);
        if(wideCapture && getChildCount()>0){
            int unit=Math.max(1,Math.min(getMeasuredWidth()/9,getMeasuredHeight()/16));
            getChildAt(0).measure(View.MeasureSpec.makeMeasureSpec(unit*9,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(unit*16,View.MeasureSpec.EXACTLY));
        }
    }
}
