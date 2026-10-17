package mx.coberturapunto;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.Button;

/** Small native controls: vector strokes, system ripples and touch feedback. */
final class NativeUi {
    static android.graphics.drawable.Drawable rounded(int color,float radius){
        GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(radius);return shape;
    }
    static void button(Button button,int fill,int ink,int kind,float density){
        button.setStateListAnimator(null);button.setElevation(0);
        button.setAllCaps(false);button.setTextSize(14);button.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));
        button.setMinHeight(0);button.setMinimumHeight(0);button.setMinWidth(0);button.setMinimumWidth(0);
        button.setPadding((int)(16*density),0,(int)(16*density),0);
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{(ink&0xffffff)|0x61000000,ink}));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf((ink&0xffffff)|0x24000000),rounded(fill,28*density),null));
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(new Icon(kind,ink,density),null,null,null);
        button.setCompoundDrawablePadding((int)(8*density));
        button.setOnTouchListener((v,event)->{
            if(!v.isEnabled())return false;
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN)v.animate().scaleX(.96f).scaleY(.96f).setDuration(90).start();
            if(event.getActionMasked()==MotionEvent.ACTION_UP || event.getActionMasked()==MotionEvent.ACTION_CANCEL)v.animate().scaleX(1).scaleY(1).setDuration(150).start();
            return false;
        });
    }
    static final class Icon extends Drawable {
        final int kind,color,size; final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Icon(int kind,int color,float density){this.kind=kind;this.color=color;size=(int)(22*density);}
        public int getIntrinsicWidth(){return size;} public int getIntrinsicHeight(){return size;}
        public void draw(Canvas canvas){
            canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/24f,getBounds().height()/24f);
            p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.9f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            if(kind==0){canvas.drawCircle(10.5f,10.5f,6.5f,p);canvas.drawLine(15.5f,15.5f,21,21,p);}
            else if(kind==1){canvas.drawCircle(12,12,7,p);canvas.drawCircle(12,12,2,p);canvas.drawLine(12,2,12,5,p);canvas.drawLine(12,19,12,22,p);canvas.drawLine(2,12,5,12,p);canvas.drawLine(19,12,22,12,p);}
            else if(kind==2){Path path=new Path();path.moveTo(3,7);path.lineTo(7,7);path.lineTo(9,4);path.lineTo(15,4);path.lineTo(17,7);path.lineTo(21,7);path.lineTo(21,20);path.lineTo(3,20);path.close();canvas.drawPath(path,p);canvas.drawCircle(12,13,4,p);}
            else if(kind==3){canvas.drawLine(5,12,10,17,p);canvas.drawLine(10,17,20,6,p);}
            else if(kind==4){canvas.drawLine(12,4,12,14,p);canvas.drawLine(8,10,12,14,p);canvas.drawLine(16,10,12,14,p);canvas.drawLine(5,18,19,18,p);}
            else if(kind==5){canvas.drawLine(6,6,18,18,p);canvas.drawLine(18,6,6,18,p);}
            else {canvas.drawLine(5,12,10,17,p);canvas.drawLine(10,17,20,6,p);}
            canvas.restore();
        }
        public void setAlpha(int a){p.setAlpha(a);}public void setColorFilter(ColorFilter filter){p.setColorFilter(filter);}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
