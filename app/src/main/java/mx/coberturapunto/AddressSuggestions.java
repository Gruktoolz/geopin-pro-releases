package mx.coberturapunto;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.json.*;

/** One search session for the native field and the selected website's field. */
final class AddressSuggestions implements AutoCloseable {
    interface Mirror {
        void request(String text,int generation,boolean fromWeb);
        void results(String text,int generation,JSONArray rows);
        void selected(String label);
        void dismiss();
    }
    private final Activity activity;
    private final EditText input;
    private final LinearLayout panel;
    private final Consumer<Address> selected;
    private final SearchMemory memory;
    private final SearchCatalog catalog;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor localWorker=worker(),geocoderWorker=worker();
    private final java.util.concurrent.ExecutorService enrichment=Executors.newSingleThreadExecutor();
    private final Set<String> attemptedAreas=new HashSet<>();
    private static ThreadPoolExecutor worker(){return new ThreadPoolExecutor(1,1,10,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),new ThreadPoolExecutor.DiscardOldestPolicy());}
    private final int surface,ink,accent;
    private int generation;
    private volatile boolean closed;
    private boolean muted,webActive,active;
    private boolean expanded=true;
    private float handleDownY;
    private Runnable debounce;
    private Mirror mirror;
    private String text="";
    private List<Address> local=Collections.emptyList(),device=Collections.emptyList(),website=Collections.emptyList(),choices=Collections.emptyList();
    AddressSuggestions(Activity activity,EditText input,LinearLayout panel,int surface,int ink,int accent,SearchMemory memory,Consumer<Address> selected){
        this.activity=activity;this.input=input;this.panel=panel;this.surface=surface;this.ink=ink;this.accent=accent;this.memory=memory;this.selected=selected;
        catalog=new SearchCatalog(activity);
        panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(12),0,dp(12),dp(4));panel.setVisibility(View.GONE);
        panel.setBackground(NativeUi.rounded(surface,dp(16)));panel.setClickable(true);panel.setElevation(dp(8));
        panel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        input.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){if(!muted && input.hasFocus()){webActive=false;changed(input.getText().toString());}}
            public void afterTextChanged(Editable s){}
        });
        input.setOnFocusChangeListener((v,focused)->{
            if(focused){webActive=false;if(LocationInput.isCoordinate(input.getText().toString()))setText("");expanded=true;changed(input.getText().toString());}
            else if(!webActive)dismiss();
        });
        localWorker.execute(()->{try{catalog.prepare();}catch(Exception ignored){}});
    }
    void setMirror(Mirror mirror){this.mirror=mirror;}
    void expand(){
        if(!active||choices.isEmpty()||expanded)return;
        expanded=true;render();
    }
    void pageReady(){if(active)changed(text);else if(mirror!=null)mirror.selected(input.getText().toString());}
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private void setText(String value){muted=true;input.setText(value);input.setSelection(input.length());muted=false;}
    void fromWeb(String value){webActive=true;input.clearFocus();setText(value);changed(value);}
    void webBlurred(){if(webActive)dismiss();}
    static boolean eligible(String value){return SearchPolicy.eligible(value);}
    private void changed(String value){
        String next=value.trim();if(!next.equals(text))expanded=true;
        cancel();active=true;text=next;final int id=generation;
        local=memory.matching(text);device=Collections.emptyList();website=Collections.emptyList();
        if(mirror!=null)mirror.request(value,id,webActive);
        publish();
        if(!eligible(text))return;
        final String query=text;final SearchMemory.Area area=memory.area();final List<SearchMemory.Area> workAreas=memory.workAreas();final List<Address> recent=new ArrayList<>(local);
        localWorker.execute(()->{
            List<Address> found=new ArrayList<>(recent);
            try{found.addAll(catalog.search(query,workAreas));}catch(Exception ignored){}
            handler.post(()->{if(current(query,id)){local=found;publish();}});
        });
        // The offline catalog handles 1-2 characters immediately. The OS provider enriches full words.
        if(text.length()>=3 && Geocoder.isPresent()){
            debounce=()->requestDevice(query,id,area);handler.postDelayed(debounce,350);
        }
    }
    private void requestDevice(String query,int id,SearchMemory.Area area){
        if(!current(query,id))return;
        Geocoder geocoder=new Geocoder(activity.getApplicationContext(),new Locale("es","MX"));
        if(Build.VERSION.SDK_INT>=33){
            Geocoder.GeocodeListener callback=new Geocoder.GeocodeListener(){
                public void onGeocode(List<Address> rows){handler.post(()->receivedDevice(query,id,area,rows));}
                public void onError(String error){handler.post(()->receivedDevice(query,id,area,Collections.emptyList()));}
            };
            try{if(area==null)geocoder.getFromLocationName(query,12,callback);
                else geocoder.getFromLocationName(query,12,area.south(),area.west(),area.north(),area.east(),callback);
            }catch(RuntimeException failure){receivedDevice(query,id,area,Collections.emptyList());}
        }else{
            geocoderWorker.execute(()->{
                List<Address> rows;
                try{rows=area==null?geocoder.getFromLocationName(query,12):geocoder.getFromLocationName(query,12,area.south(),area.west(),area.north(),area.east());}
                catch(Exception failure){rows=Collections.emptyList();}
                final List<Address> found=rows;handler.post(()->receivedDevice(query,id,area,found));
            });
        }
    }
    private void receivedDevice(String query,int id,SearchMemory.Area area,List<Address> rows){
        if(!current(query,id))return;
        if((rows==null || rows.isEmpty())&&area!=null){requestDevice(query,id,null);return;}
        device=rows==null?Collections.emptyList():rows;publish();
    }
    void receiveWebsite(int id,JSONArray rows){
        if(!current(text,id) || rows==null)return;
        List<Address> found=new ArrayList<>();
        for(int i=0;i<Math.min(24,rows.length());i++){
            JSONObject row=rows.optJSONObject(i);if(row==null)continue;
            String label=row.optString("label","");if(label.isEmpty() || label.length()>300)continue;
            double lat=row.optDouble("lat",Double.NaN),lon=row.optDouble("lon",Double.NaN);
            if(!Double.isFinite(lat)||!Double.isFinite(lon)||lat<14||lat>33||lon< -119||lon> -86)continue;
            Address a=new Address(new Locale("es","MX"));a.setAddressLine(0,label);a.setLatitude(lat);a.setLongitude(lon);a.setCountryCode("MX");
            // The source's city must come from its coordinates, not an old currentCity value.
            Bundle info=new Bundle();info.putString("source","MEGA");info.putString("name",label.split(",",2)[0]);a.setExtras(info);found.add(a);
        }
        website=found;publish();
    }
    private boolean current(String query,int id){return !closed && active && id==generation && query.equals(text) && !activity.isFinishing() && !activity.isDestroyed();}
    private static String name(Address a){Bundle b=a.getExtras();return b!=null?b.getString("name",a.getAddressLine(0).split(",",2)[0]):a.getAddressLine(0).split(",",2)[0];}
    private List<Address> merged(){
        List<Address> all=new ArrayList<>();all.addAll(website);all.addAll(device);all.addAll(local);
        List<Address> unique=new ArrayList<>();List<SearchMemory.Area> workAreas=memory.workAreas();
        for(Address a:all){
            if(a==null || a.getAddressLine(0)==null || !LocationInput.isMexico(a))continue;
            boolean duplicate=false;
            for(Address b:unique){
                if(SearchPolicy.normalize(a.getAddressLine(0)).equals(SearchPolicy.normalize(b.getAddressLine(0))) ||
                   (SearchPolicy.normalize(name(a)).equals(SearchPolicy.normalize(name(b))) &&
                   ((!a.hasLatitude()||!b.hasLatitude())?sameCity(a,b):SearchPolicy.distance(a.getLatitude(),a.getLongitude(),b.getLatitude(),b.getLongitude())<.0001))){duplicate=true;break;}
            }
            if(!duplicate)unique.add(a);
        }
        SearchPolicy.SearchIntent intent=SearchPolicy.intent(text);
        if(intent.includesBoth())unique.sort(Comparator.comparingInt((Address a)->explicitLocationMatch(a)?0:1)
                .thenComparingInt(a->workAreaRank(a,workAreas)).thenComparingDouble(a->rank(a,workAreas,intent))
                .thenComparingInt(a->categoryRank(a)==2?0:categoryRank(a)));
        else unique.sort(Comparator.comparingInt((Address a)->explicitLocationMatch(a)?0:1)
                .thenComparingInt(a->workAreaRank(a,workAreas)).thenComparingInt(this::categoryRank)
                .thenComparingDouble(a->rank(a,workAreas,intent)));
        return new ArrayList<>(unique.subList(0,Math.min(12,unique.size())));
    }
    private boolean sameCity(Address a,Address b){String x=a.getLocality(),y=b.getLocality();return x!=null&&y!=null&&SearchPolicy.normalize(x).equals(SearchPolicy.normalize(y));}
    private int categoryRank(Address a){
        Bundle info=a.getExtras();String kind=info!=null?info.getString("kind",""):"";
        if("settlement".equals(kind))return 0;
        if("street".equals(kind))return 2;
        String label=SearchPolicy.normalize(a.getAddressLine(0));
        String name=SearchPolicy.normalize(name(a));
        if(label.matches("^(colonia|fraccionamiento|barrio|unidad|residencial)\\b.*")||name.matches("^(colonia|fraccionamiento|barrio|unidad|residencial)\\b.*"))return 0;
        if(label.matches("^(calle|avenida|av|boulevard|blvd|calzada|cerrada|circuito|andador|privada|carretera|camino)\\b.*")||name.matches("^(calle|avenida|av|boulevard|blvd|calzada|cerrada|circuito|andador|privada|carretera|camino)\\b.*"))return 2;
        if(a.getSubLocality()!=null&&!a.getSubLocality().trim().isEmpty())return 0;
        if(a.getThoroughfare()!=null&&!a.getThoroughfare().trim().isEmpty())return 2;
        return 1;
    }
    private boolean explicitLocationMatch(Address a){
        String query=SearchPolicy.normalize(text);if(query.isEmpty())return false;
        String[] fields={a.getLocality(),a.getSubAdminArea(),a.getAdminArea()};
        for(String field:fields){String place=SearchPolicy.normalize(field);if(place.length()>=3&&query.contains(place))return true;}
        String label=SearchPolicy.normalize(a.getAddressLine(0));
        for(String field:fields){String place=SearchPolicy.normalize(field);if(place.length()>=3&&label.contains(place)&&query.contains(place))return true;}
        return false;
    }
    private int workAreaRank(Address a,List<SearchMemory.Area> areas){
        if(areas.isEmpty())return 1;
        int best=areas.size()+1;
        String city=SearchPolicy.normalize(a.getLocality());
        String label=SearchPolicy.normalize(a.getAddressLine(0));
        for(int i=0;i<areas.size();i++){
            SearchMemory.Area area=areas.get(i);String known=SearchPolicy.normalize(area.city);
            if(!known.isEmpty()&&(city.equals(known)||label.contains(known)))best=Math.min(best,i);
            if(a.hasLatitude()&&a.hasLongitude()&&SearchPolicy.distance(a.getLatitude(),a.getLongitude(),area.lat,area.lon)<=.12)best=Math.min(best,i);
        }
        return best;
    }
    private double rank(Address a,List<SearchMemory.Area> areas,SearchPolicy.SearchIntent intent){
        double distance=200;
        for(SearchMemory.Area area:areas){
            double candidate=!a.hasLatitude()?((a.getLocality()!=null&&!area.city.isEmpty()&&SearchPolicy.normalize(a.getLocality()).equals(SearchPolicy.normalize(area.city)))?.03:200):SearchPolicy.distance(a.getLatitude(),a.getLongitude(),area.lat,area.lon);
            distance=Math.min(distance,candidate);
        }
        String matchText=intent.includesBoth()?(categoryRank(a)==0?intent.settlement():categoryRank(a)==2?intent.street():text):text;
        double prefix=SearchPolicy.normalize(name(a)).startsWith(SearchPolicy.normalize(matchText))?0:.4;
        return intent.includesBoth()?prefix*100000+Math.min(200,distance)*10:Math.min(200,distance)*10+prefix;
    }
    private void publish(){
        choices=eligible(text)?merged():Collections.emptyList();
        if(webActive)hide();else render();
        if(mirror!=null){
            JSONArray rows=new JSONArray();for(int i=0;i<choices.size();i++)try{
                Address a=choices.get(i);rows.put(new JSONObject().put("id",i).put("label",a.getAddressLine(0)).put("source",a.getExtras()!=null?a.getExtras().getString("source","Dispositivo"):"Dispositivo"));
            }catch(JSONException ignored){}
            mirror.results(text,generation,rows);
        }
    }
    void chooseWeb(int id,int row){if(current(text,id)&&row>=0&&row<choices.size())choose(choices.get(row));}
    private void choose(Address a){
        String label=a.getAddressLine(0);dismiss();setText(label);input.clearFocus();
        if(mirror!=null)mirror.selected(label);
        if(a.hasLatitude()&&a.hasLongitude())memory.remember(a);
        selected.accept(a);
    }
    void areaChanged(){SearchMemory.Area area=memory.area();input.setHint(area!=null&&!area.city.isEmpty()?"Buscar en "+area.city:"Dirección o enlace de ubicación");if(active)changed(text);}
    void enrich(double lat,double lon,Consumer<SearchCatalog.City> callback){
        final String tile=Math.round(lat*10)+":"+Math.round(lon*10);
        localWorker.execute(()->{
            try{SearchCatalog.City city=catalog.nearest(lat,lon);handler.post(()->{if(!closed)callback.accept(city);});}
            catch(Exception ignored){}
        });
        if(attemptedAreas.size()>40)attemptedAreas.clear();
        if(!attemptedAreas.add(tile))return;
        enrichment.execute(()->{
            try{if(!closed)catalog.enrich(lat,lon);handler.post(()->{if(!closed&&active)changed(text);});}
            catch(Exception ignored){}
        });
    }
    private void render(){
        hide();if(choices.isEmpty()||!active)return;
        LinearLayout header=new LinearLayout(activity);header.setOrientation(LinearLayout.VERTICAL);header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout gripRow=new LinearLayout(activity);gripRow.setGravity(Gravity.CENTER);
        View grip=new View(activity);grip.setBackground(NativeUi.rounded((ink&0x00ffffff)|0x66000000,dp(3)));
        gripRow.addView(grip,new LinearLayout.LayoutParams(dp(34),dp(4)));header.addView(gripRow,new LinearLayout.LayoutParams(-1,dp(8)));
        LinearLayout titleRow=new LinearLayout(activity);titleRow.setOrientation(LinearLayout.HORIZONTAL);titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=new TextView(activity);title.setText("Sugerencias · "+choices.size());title.setTextColor(ink);title.setTextSize(12);title.setSingleLine(true);
        titleRow.addView(title,new LinearLayout.LayoutParams(0,dp(30),1));
        TextView arrow=new TextView(activity);arrow.setText(expanded?"⌃":"⌄");arrow.setTextColor(ink);arrow.setTextSize(22);arrow.setGravity(Gravity.CENTER);arrow.setMinWidth(dp(40));
        titleRow.addView(arrow,new LinearLayout.LayoutParams(dp(40),dp(30)));header.addView(titleRow,new LinearLayout.LayoutParams(-1,dp(30)));
        header.setClickable(true);header.setFocusable(true);
        LinearLayout details=new LinearLayout(activity);details.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);scroll.setClickable(true);scroll.setFocusable(false);
        LinearLayout list=new LinearLayout(activity);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(0,0,dp(2),0);
        scroll.addView(list,new FrameLayout.LayoutParams(-1,-2));
        for(Address address:choices){
            TextView row=new TextView(activity);row.setText(address.getAddressLine(0));row.setTextColor(ink);row.setTextSize(13);row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16),dp(8),dp(12),dp(8));row.setMinHeight(dp(50));row.setMaxLines(2);row.setEllipsize(android.text.TextUtils.TruncateAt.END);row.setFocusable(true);
            row.setBackground(new RippleDrawable(ColorStateList.valueOf((accent&0xffffff)|0x24000000),NativeUi.rounded(surface,dp(12)),null));
            row.setContentDescription("Usar ubicación: "+address.getAddressLine(0));row.setOnClickListener(v->choose(address));
            LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(-1,-2);rowParams.bottomMargin=dp(4);list.addView(row,rowParams);
        }
        int maximum=Math.min(dp(210),activity.getResources().getDisplayMetrics().heightPixels/3);
        details.addView(scroll,new LinearLayout.LayoutParams(-1,Math.min(maximum,choices.size()*dp(54))));
        LinearLayout footer=new LinearLayout(activity);footer.setGravity(Gravity.CENTER_VERTICAL);
        TextView credit=new TextView(activity);credit.setText("GeoNames · INEGI · MEGA · Dispositivo");credit.setTextColor(ink);credit.setTextSize(9);credit.setSingleLine(true);
        footer.addView(credit,new LinearLayout.LayoutParams(0,-2,1));
        TextView hint=new TextView(activity);hint.setText(expanded?"Desliza arriba para ocultar":"Desliza abajo para ver");hint.setTextColor(ink);hint.setTextSize(9);hint.setSingleLine(true);
        footer.addView(hint);details.addView(footer,new LinearLayout.LayoutParams(-1,dp(20)));
        header.setContentDescription(expanded?"Contraer sugerencias; desliza hacia arriba":"Expandir sugerencias; desliza hacia abajo");
        header.setOnTouchListener((view,event)->{
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:handleDownY=event.getRawY();return true;
                case MotionEvent.ACTION_MOVE:return true;
                case MotionEvent.ACTION_UP:
                    float delta=event.getRawY()-handleDownY;boolean next;
                    if(Math.abs(delta)>dp(24))next=delta>0;else next=!expanded;
                    setExpanded(next,details,arrow,hint);return true;
                case MotionEvent.ACTION_CANCEL:return true;
                default:return true;
            }
        });
        panel.addView(header,new LinearLayout.LayoutParams(-1,dp(38)));
        details.setVisibility(expanded?View.VISIBLE:View.GONE);panel.addView(details,new LinearLayout.LayoutParams(-1,-2));
        panel.setVisibility(View.VISIBLE);
    }
    private void setExpanded(boolean value,View details,TextView arrow,TextView hint){
        if(expanded==value)return;expanded=value;arrow.setText(expanded?"⌃":"⌄");
        hint.setText(expanded?"Desliza arriba para ocultar":"Desliza abajo para ver");
        if(expanded){details.setVisibility(View.VISIBLE);details.setAlpha(0f);details.setTranslationY(-dp(12));details.animate().alpha(1f).translationY(0).setDuration(160).start();}
        else details.animate().alpha(0f).translationY(-dp(12)).setDuration(150).withEndAction(()->{details.setVisibility(View.GONE);details.setAlpha(1f);details.setTranslationY(0);}).start();
    }
    private void hide(){panel.removeAllViews();panel.setVisibility(View.GONE);}
    private void cancel(){generation++;if(debounce!=null)handler.removeCallbacks(debounce);localWorker.getQueue().clear();geocoderWorker.getQueue().clear();hide();}
    void dismiss(){cancel();active=false;choices=Collections.emptyList();if(mirror!=null)mirror.dismiss();}
    boolean visible(){return active&&!choices.isEmpty();}
    @Override public void close(){closed=true;dismiss();localWorker.shutdownNow();geocoderWorker.shutdownNow();enrichment.shutdownNow();
        // Closing on this worker after its last DB operation avoids a cursor/close race.
        new Thread(()->{try{localWorker.awaitTermination(20,TimeUnit.SECONDS);enrichment.awaitTermination(45,TimeUnit.SECONDS);if(localWorker.isTerminated()&&enrichment.isTerminated())catalog.close();}catch(InterruptedException ignored){Thread.currentThread().interrupt();}},"search-close").start();}
}
