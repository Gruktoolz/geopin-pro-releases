package mx.coberturapunto;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Ephemeral, inline address hints. Never moves the map until one is selected. */
final class AddressSuggestions implements AutoCloseable {
    private final Activity activity;
    private final EditText input;
    private final LinearLayout panel;
    private final Consumer<Address> selected;
    private final SearchMemory memory;
    private final Handler handler=new Handler(Looper.getMainLooper());
    // Older Android geocoders block; their work never shares the capture/search worker.
    // Only the latest waiting request is retained, so slow providers cannot grow a queue.
    private final ThreadPoolExecutor legacyWorker=new ThreadPoolExecutor(1,1,10,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1),new ThreadPoolExecutor.DiscardOldestPolicy());
    private final Map<String,List<Address>> cache=new LinkedHashMap<String,List<Address>>(16,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,List<Address>> entry){return size()>12;}
    };
    private final int surface,ink,accent;
    private int generation;
    private boolean closed,muted;
    private Runnable debounce;
    private Runnable timeout;
    AddressSuggestions(Activity activity,EditText input,LinearLayout panel,int surface,int ink,int accent,SearchMemory memory,Consumer<Address> selected){
        this.memory=memory;this.activity=activity;this.input=input;this.panel=panel;this.surface=surface;this.ink=ink;this.accent=accent;this.selected=selected;
        panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(12),0,dp(12),dp(8));panel.setVisibility(View.GONE);
        panel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        input.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){if(!muted)changed();}
            public void afterTextChanged(Editable s){}
        });
        input.setOnFocusChangeListener((v,focused)->{
            if(!focused)dismiss();
            else if(LocationInput.isCoordinate(input.getText().toString())){muted=true;input.setText("");muted=false;}
        });
    }
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private void changed(){
        dismiss();
        String text=input.getText().toString().trim();
        if(!input.hasFocus() || !eligible(text))return;
        List<Address> local=memory.matching(text);
        for(List<Address> saved:cache.values())for(Address a:saved)if(SearchMemory.matches(a.getAddressLine(0),text))local.add(a);
        render(unique(local));
        if(!Geocoder.isPresent())return;
        final int id=generation;
        debounce=()->lookup(text,id);handler.postDelayed(debounce,300);
    }
    void areaChanged(){
        SearchMemory.Area area=memory.area();
        input.setHint(area!=null && !area.city.isEmpty()?"Buscar en "+area.city:"Dirección o enlace de ubicación");
        if(input.hasFocus() && eligible(input.getText().toString().trim()))changed();
    }
    private String cacheKey(String text){SearchMemory.Area area=memory.area();return (area==null?"global":area.key())+":"+SearchMemory.normalize(text);}
    static boolean eligible(String text){
        if(text.length()<3 || text.length()>180 || text.contains(":") || text.contains("/") || text.contains("\n"))return false;
        // Pasted coordinates and location links keep their existing exact-point path.
        for(int i=0;i<text.length();i++)if(Character.isLetter(text.charAt(i)))return true;
        return false;
    }
    private void lookup(String text,int id){
        if(!current(text,id))return;
        List<Address> cached=cache.get(cacheKey(text));
        if(cached!=null){render(cached);return;}
        timeout=()->{if(current(text,id))generation++;};handler.postDelayed(timeout,7000);
        request(text,id,memory.area());
    }
    private void request(String text,int id,SearchMemory.Area area){
        if(!current(text,id))return;
        Geocoder geocoder=new Geocoder(activity.getApplicationContext(),new Locale("es","MX"));
        if(Build.VERSION.SDK_INT>=33){
            Geocoder.GeocodeListener callback=new Geocoder.GeocodeListener(){
                @Override public void onGeocode(List<Address> addresses){handler.post(()->received(text,id,area,addresses));}
                @Override public void onError(String message){handler.post(()->received(text,id,area,Collections.emptyList()));}
            };
            try{if(area==null)geocoder.getFromLocationName(text,5,callback);
                else geocoder.getFromLocationName(text,5,area.south(),area.west(),area.north(),area.east(),callback);
            }catch(RuntimeException failure){received(text,id,area,Collections.emptyList());}
        }else{
            legacyWorker.execute(()->{
                List<Address> addresses;
                try{addresses=area==null?geocoder.getFromLocationName(text,5):geocoder.getFromLocationName(text,5,area.south(),area.west(),area.north(),area.east());}
                catch(Exception failure){addresses=Collections.emptyList();}
                final List<Address> found=addresses;handler.post(()->received(text,id,area,found));
            });
        }
    }
    private void received(String text,int id,SearchMemory.Area area,List<Address> addresses){
        if(!current(text,id))return;
        if((addresses==null || addresses.isEmpty()) && area!=null){request(text,id,null);return;}
        deliver(text,id,addresses);
    }
    private boolean current(String text,int id){return !closed && id==generation && input.hasFocus() && text.equals(input.getText().toString().trim()) && !activity.isFinishing() && !activity.isDestroyed();}
    private void deliver(String text,int id,List<Address> addresses){
        if(!current(text,id))return;
        if(timeout!=null)handler.removeCallbacks(timeout);
        List<Address> merged=new ArrayList<>();if(addresses!=null)merged.addAll(addresses);merged.addAll(memory.matching(text));
        List<Address> unique=unique(merged);
        if(!unique.isEmpty())cache.put(cacheKey(text),unique);
        if(!unique.isEmpty())render(unique);
        if((activity.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)
            android.util.Log.i("CoverageSuggestions","results="+unique.size()+" generation="+id);
    }
    private List<Address> unique(List<Address> addresses){
        List<Address> list=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Address a:addresses){
            if(a.getAddressLine(0)==null || !a.hasLatitude() || !a.hasLongitude() || !LocationInput.isMexico(a))continue;
            if(seen.add(a.getAddressLine(0))){list.add(a);if(list.size()==3)break;}
        }
        return list;
    }
    private void render(List<Address> addresses){
        hide();
        for(Address address:addresses){
            LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(16),dp(8),dp(12),dp(8));
            row.setBackground(new RippleDrawable(ColorStateList.valueOf((accent&0xffffff)|0x24000000),NativeUi.rounded(surface,dp(16)),null));
            row.setMinimumHeight(dp(52));row.setFocusable(true);row.setClickable(true);
            ImageView icon=new ImageView(activity);icon.setImageDrawable(new NativeUi.Icon(1,accent,activity.getResources().getDisplayMetrics().density));
            row.addView(icon,new LinearLayout.LayoutParams(dp(20),dp(20)));
            TextView label=new TextView(activity);label.setText(address.getAddressLine(0));label.setTextColor(ink);label.setTextSize(13);
            label.setMaxLines(2);label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setPadding(dp(12),0,0,0);
            row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            row.setContentDescription("Usar ubicación: "+address.getAddressLine(0));
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(4);panel.addView(row,params);
            row.setOnClickListener(v->{
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                memory.remember(address);dismiss();muted=true;input.setText(address.getAddressLine(0));input.setSelection(input.length());muted=false;
                input.clearFocus();areaChanged();selected.accept(address);
            });
        }
        if(!addresses.isEmpty())panel.setVisibility(View.VISIBLE);
    }
    private void hide(){panel.removeAllViews();panel.setVisibility(View.GONE);}
    void dismiss(){generation++;if(debounce!=null)handler.removeCallbacks(debounce);if(timeout!=null)handler.removeCallbacks(timeout);legacyWorker.getQueue().clear();hide();}
    boolean visible(){return panel.getVisibility()==View.VISIBLE;}
    @Override public void close(){closed=true;dismiss();legacyWorker.shutdownNow();cache.clear();}
}
