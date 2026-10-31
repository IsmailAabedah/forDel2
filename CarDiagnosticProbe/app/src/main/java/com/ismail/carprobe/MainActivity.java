package com.ismail.carprobe;

import android.app.Activity;
import android.content.*;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private LinearLayout root, logBox;
    private TextView status;
    private final StringBuilder report = new StringBuilder();
    private BroadcastReceiver wheelReceiver;
    private ServiceConnection usbConnection;
    private boolean usbBound = false;
    private boolean canBound = false;
    private IBinder canService;
    private Binder canCallback;
    private ServiceConnection canConnection;
    private Binder logicCallback;
    private Binder mcuCallback;
    private IBinder logicService, mcuService;
    private ContentObserver settingsObserver;
    private final ExecutorService binderWorker = Executors.newSingleThreadExecutor();

    private String stamp() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private void log(String s) {
        String line = "[" + stamp() + "] " + s;
        report.append(line).append("\n");
        runOnUiThread(() -> {
            if (logBox != null) {
                TextView t = new TextView(this);
                t.setText(line);
                t.setTextColor(0xffdce6f2);
                t.setTextSize(13);
                t.setPadding(5, 3, 5, 3);
                logBox.addView(t, 0);
            }
            if (status != null) status.setText("تم تسجيل حدث جديد • " + report.toString().split("\n").length);
        });
        android.util.Log.i("CarProbe", line);
    }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        log("بدء ZhongHong Diagnostic Probe v1.2");
        log("Android=" + Build.VERSION.RELEASE + " SDK=" + Build.VERSION.SDK_INT
                + " ABI=" + Build.CPU_ABI + " device=" + Build.DEVICE);
        log("الشاشة=" + getResources().getDisplayMetrics().widthPixels + "x"
                + getResources().getDisplayMetrics().heightPixels);
        readBluetoothSnapshot();
        registerWheelBroadcast();
        registerSettingsObserver();
        bindCanService();
        log("بدء اتصالات Binder في خيط خلفي لتجنب تجميد الواجهة...");
        binderWorker.execute(() -> {
            registerLogicCallback();
            registerMcuHardKeyListener();
            log("انتهت مرحلة محاولة تسجيل مستمعي Logic وMCU.");
        });
        log("جاهز. أوقف السيارة تماماً قبل الضغط على أزرار المقود.");
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff101923);
        root.setPadding(18, 12, 18, 10);

        TextView title = new TextView(this);
        title.setText("فاحص نظام ZhongHong");
        title.setTextColor(0xffffffff);
        title.setTextSize(23);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, 42));

        status = new TextView(this);
        status.setText("جارٍ تهيئة الفحوصات...");
        status.setTextColor(0xff83d6bd);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(-1, 32));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        addButton(buttons, "حالة البلوتوث", this::readBluetoothSnapshot);
        addButton(buttons, "اختبار USB", this::probeUsbService);
        addButton(buttons, "فحص CAN", this::refreshCan);
        addButton(buttons, "تحديث الفحوصات", this::refreshAll);
        addButton(buttons, "نسخ التقرير", this::copyReport);
        root.addView(buttons, new LinearLayout.LayoutParams(-1, 54));

        TextView hint = new TextView(this);
        hint.setText("اختبار المقود والتعشيق: والسيارة متوقفة تماماً مع تثبيت الفرامل، جرّب أزرار المقود ثم حرّك المحدد بين D و S وجرّب + و- إذا كان ذلك آمناً. يسجل التطبيق القيم الخام دون إرسال أوامر للسيارة. منفذ 12V لا يملك حالياً إشارة كشف مؤكدة.");
        hint.setTextColor(0xffffd27a);
        hint.setTextSize(14);
        hint.setPadding(4, 6, 4, 8);
        root.addView(hint);

        ScrollView scroll = new ScrollView(this);
        logBox = new LinearLayout(this);
        logBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(logBox);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void addButton(LinearLayout parent, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setOnClickListener(v -> action.run());
        parent.addView(b, new LinearLayout.LayoutParams(0, -1, 1));
    }

    private void refreshAll() {
        readBluetoothSnapshot();
        probeUsbService();
        refreshCan();
        log("تم تنفيذ تحديث يدوي للفحوصات.");
    }

    private void bindCanService() {
        Intent intent = new Intent();
        intent.setClassName("com.zhonghong.can", "com.zhonghong.can.CanService");
        canConnection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                canBound = true;
                canService = binder;
                log("CAN_SERVICE_CONNECTED component=" + name.flattenToShortString()
                        + " descriptor=" + safeDescriptor(binder));
                registerCanCallback();
                refreshCan();
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                canBound = false;
                canService = null;
                log("CAN_SERVICE_DISCONNECTED " + name);
            }
        };
        try {
            boolean accepted = bindService(intent, canConnection, Context.BIND_AUTO_CREATE);
            log("CAN bindService accepted=" + accepted);
        } catch (Throwable e) { log("CAN bind error=" + e); }
    }

    private String safeDescriptor(IBinder binder) {
        try { return binder.getInterfaceDescriptor(); }
        catch (Throwable e) { return "unavailable:" + e.getClass().getSimpleName(); }
    }

    private void registerCanCallback() {
        if (canService == null) return;
        canCallback = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code == 1) {
                    data.enforceInterface("com.zhonghong.cancommon.ICanDataCallback");
                    final int moduleType = data.readInt();
                    if (reply != null) reply.writeNoException();
                    binderWorker.execute(() -> readCanModule(moduleType));
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };
        canCallback.attachInterface(null, "com.zhonghong.cancommon.ICanDataCallback");
        try {
            Parcel d = Parcel.obtain(), r = Parcel.obtain();
            d.writeInterfaceToken("com.zhonghong.cancommon.ICanInterface");
            d.writeStrongBinder(canCallback);
            boolean ok = canService.transact(1, d, r, 0);
            if (ok) { r.readException(); log("CAN callback registration accepted."); }
            else log("CAN callback registration returned false.");
            d.recycle(); r.recycle();
        } catch (Throwable e) { log("CAN callback registration error=" + e); }
    }

    private void refreshCan() {
        if (canService == null) {
            log("CAN: الخدمة لم تتصل بعد؛ انتظر قليلاً ثم أعد الفحص.");
            return;
        }
        binderWorker.execute(() -> {
            for (int module = 1; module <= 4; module++) readCanModule(module);
        });
    }

    private void readCanModule(int moduleType) {
        IBinder binder = canService;
        if (binder == null) return;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken("com.zhonghong.cancommon.ICanInterface");
            d.writeInt(moduleType);
            boolean ok = binder.transact(5, d, r, 0);
            if (!ok) { log("CAN_GET module=" + moduleType + " transact=false"); return; }
            r.readException();
            int hasValue = r.readInt();
            if (hasValue == 0) { log("CAN_GET module=" + moduleType + " no data"); return; }
            int count = r.readInt();
            log("CAN_GET module=" + moduleType + " frameCount=" + count);
            for (int n = 0; n < count; n++) {
                int frameId = r.readInt();
                int len = r.readByte() & 0xff;
                byte[] frame = len > 0 ? r.createByteArray() : null;
                if (frame == null) {
                    log("CAN_FRAME module=" + moduleType + " id=" + frameId + " null");
                    continue;
                }
                StringBuilder hex = new StringBuilder();
                for (byte b : frame) hex.append(String.format(Locale.US, "%02X ", b & 0xff));
                if (frameId == 1218 && frame.length >= 11) {
                    int shifter = (frame[4] >> 2) & 0x0f;
                    int winter = (frame[4] >> 6) & 0x01;
                    int speed = frame[8] & 0xff;
                    int ratio = (frame[6] & 0xff) | ((frame[7] & 0xff) << 8);
                    log("CAN_1218 shifterRaw=" + shifter + " gear=" + gearName(shifter)
                            + " winterModeRaw=" + winter + " speedRaw=" + speed
                            + " overallRatioRaw=" + ratio + " bytes=" + hex);
                } else {
                    log("CAN_FRAME module=" + moduleType + " id=" + frameId
                            + " len=" + len + " bytes=" + hex);
                }
            }
        } catch (Throwable e) { log("CAN_GET module=" + moduleType + " error=" + e); }
        finally { d.recycle(); r.recycle(); }
    }

    private String gearName(int raw) {
        switch (raw) {
            case 9: return "P";
            case 10: return "R";
            case 11: return "N";
            case 12: return "D";
            default: return "غير مصنف(" + raw + ")";
        }
    }

    private void registerWheelBroadcast() {
        wheelReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String key = i.getStringExtra("WHEEL_KEY");
                log("WHEEL_BROADCAST action=" + i.getAction() + " WHEEL_KEY=" + key
                        + " extras=" + i.getExtras());
            }
        };
        IntentFilter f = new IntentFilter("com.zhonghong.zuiserver.BROADCAST");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(wheelReceiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(wheelReceiver, f);
        log("تم تسجيل مستقبل البث com.zhonghong.zuiserver.BROADCAST / WHEEL_KEY.");
    }

    private IBinder service(String name) {
        try {
            Class<?> c = Class.forName("android.os.ServiceManager");
            Method m = c.getDeclaredMethod("getService", String.class);
            m.setAccessible(true);
            return (IBinder) m.invoke(null, name);
        } catch (Throwable e) {
            log("ServiceManager lookup failed " + name + ": " + e);
            return null;
        }
    }

    private void registerLogicCallback() {
        logicService = service("_logic_service");
        if (logicService == null) {
            log("LOGIC: _logic_service غير متاح.");
            return;
        }
        logicCallback = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code == 1) {
                    data.enforceInterface("com.logic.common.aidl.ILogicCallback");
                    String event = data.readString();
                    String value = data.readString();
                    String extra = data.readString();
                    log("LOGIC_EVENT event=" + event + " value=" + value + " extra=" + extra);
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };
        logicCallback.attachInterface(null, "com.logic.common.aidl.ILogicCallback");
        try {
            Parcel d = Parcel.obtain(), r = Parcel.obtain();
            d.writeInterfaceToken("com.logic.common.aidl.ILogicInterface");
            d.writeInt(3);
            d.writeStrongBinder(logicCallback);
            boolean ok = logicService.transact(1, d, r, 0);
            if (ok) { r.readException(); log("LOGIC callback registration transaction accepted."); }
            else log("LOGIC callback registration transaction returned false.");
            d.recycle(); r.recycle();
            readLogicValues();
        } catch (Throwable e) {
            log("LOGIC registration error: " + e);
        }
    }

    private void readLogicValues() {
        String[] keys = {"getSourceId", "getThirdSourceId", "isAccOn", "isInBackState",
                "isIllon", "isPowerOn", "isRunning", "getFocusState"};
        for (String key : keys) {
            try {
                Parcel d = Parcel.obtain(), r = Parcel.obtain();
                d.writeInterfaceToken("com.logic.common.aidl.ILogicInterface");
                d.writeString(key);
                d.writeString("");
                boolean ok = logicService.transact(4, d, r, 0);
                if (ok) { r.readException(); log("LOGIC_GET " + key + "=" + r.readString()); }
                else log("LOGIC_GET " + key + " transaction=false");
                d.recycle(); r.recycle();
            } catch (Throwable e) { log("LOGIC_GET " + key + " error=" + e); }
        }
    }

    private void registerMcuHardKeyListener() {
        mcuService = service("zhonghong.mcu");
        if (mcuService == null) {
            log("MCU: خدمة zhonghong.mcu غير متاحة.");
            return;
        }
        mcuCallback = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code == 2) {
                    data.enforceInterface("android.zhonghong.IDataChangedListener");
                    int domain = data.readInt();
                    int cmd = data.readInt();
                    int arg = data.readInt();
                    int pos = data.dataPosition();
                    int size = data.dataSize();
                    String tail;
                    try {
                        byte[] raw = data.marshall();
                        StringBuilder hex = new StringBuilder();
                        for (int i = Math.min(pos, raw.length); i < raw.length; i++) {
                            hex.append(String.format(Locale.US, "%02X", raw[i]));
                            if (i < raw.length - 1) hex.append(' ');
                        }
                        tail = hex.toString();
                    } catch (Throwable e) { tail = "raw-unavailable:" + e; }
                    log("MCU_CALLBACK domain=" + domain + " cmd=" + cmd + " arg=" + arg
                            + " parcelPos=" + pos + " parcelSize=" + size + " tail=" + tail);
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };
        mcuCallback.attachInterface(null, "android.zhonghong.IDataChangedListener");
        try {
            Parcel d = Parcel.obtain(), r = Parcel.obtain();
            d.writeInterfaceToken("android.zhonghong.IMcuService");
            d.writeInt(4);
            d.writeStrongBinder(mcuCallback);
            boolean ok = mcuService.transact(5, d, r, 0);
            if (ok) log("MCU domain-4 listener result=" + r.readInt());
            else log("MCU domain-4 listener transaction returned false.");
            d.recycle(); r.recycle();
        } catch (Throwable e) {
            log("MCU domain-4 registration error: " + e);
        }
    }

    private void readBluetoothSnapshot() {
        readSetting("bt_connected");
        readSetting("key_bluetooth_state");
        readSetting("key_signal_state");
        readSetting("key_battery_state");
        readSetting("sys.btcall");
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            log("AUDIO musicActive=" + am.isMusicActive()
                    + " musicVolume=" + am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    + "/" + am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        } catch (Throwable e) { log("AUDIO state error=" + e); }
    }

    private void readSetting(String key) {
        String value = null;
        try { value = Settings.System.getString(getContentResolver(), key); } catch (Throwable ignored) {}
        if (value == null) {
            try {
                Class<?> p = Class.forName("android.os.SystemProperties");
                Method m = p.getDeclaredMethod("get", String.class);
                m.setAccessible(true);
                value = (String) m.invoke(null, key);
            } catch (Throwable ignored) {}
        }
        log("STATE " + key + "=" + value);
    }

    private void registerSettingsObserver() {
        settingsObserver = new ContentObserver(new Handler()) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                log("SETTINGS_CHANGED uri=" + uri);
                readBluetoothSnapshot();
            }
        };
        String[] keys = {"bt_connected", "key_bluetooth_state", "key_signal_state", "key_battery_state"};
        for (String k : keys) {
            try { getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(k), false, settingsObserver); } catch (Throwable e) {
                log("Observer error " + k + ": " + e);
            }
        }
        log("تم تفعيل مراقبة تغيّر مؤشرات Bluetooth في Settings.System.");
    }

    private void probeUsbService() {
        if (usbBound) {
            log("USB: الاتصال بالخدمة مسجل بالفعل.");
            return;
        }
        Intent i = new Intent();
        i.setClassName("com.zhonghong.yzmusic",
                "com.zhonghong.music.model.service.UsbMusicService");
        usbConnection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                usbBound = true;
                try {
                    log("USB_SERVICE_CONNECTED component=" + name.flattenToShortString()
                            + " descriptor=" + binder.getInterfaceDescriptor()
                            + " ping=" + binder.pingBinder());
                } catch (Throwable e) { log("USB connected but descriptor read failed: " + e); }
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                usbBound = false;
                log("USB_SERVICE_DISCONNECTED " + name);
            }
        };
        try {
            boolean accepted = bindService(i, usbConnection, Context.BIND_AUTO_CREATE);
            log("USB bindService accepted=" + accepted
                    + " (هذه الخطوة لا تستدعي أوامر التشغيل ولا تغيّر الموسيقى).");
        } catch (Throwable e) { log("USB bind error=" + e); }
    }

    private void copyReport() {
        String text = report.toString();
        android.content.ClipboardManager cb =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cb.setPrimaryClip(ClipData.newPlainText("Car diagnostic report", text));
        Toast.makeText(this, "تم نسخ التقرير. الصقه هنا في المحادثة.", Toast.LENGTH_LONG).show();
        log("تم نسخ التقرير إلى الحافظة.");
    }

    @Override protected void onDestroy() {
        try { if (wheelReceiver != null) unregisterReceiver(wheelReceiver); } catch (Throwable ignored) {}
        try { if (settingsObserver != null) getContentResolver().unregisterContentObserver(settingsObserver); } catch (Throwable ignored) {}
        try { if (usbBound && usbConnection != null) unbindService(usbConnection); } catch (Throwable ignored) {}
        try { if (canBound && canConnection != null) unbindService(canConnection); } catch (Throwable ignored) {}
        binderWorker.shutdownNow();
        super.onDestroy();
    }
}
