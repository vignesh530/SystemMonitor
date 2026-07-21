package com.sysmonitor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.LayoutManager;
import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.GraphicsCard;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.hardware.PowerSource;
import oshi.software.os.OSFileStore;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

public class SystemMonitor extends JFrame {

    private final SystemInfo si = new SystemInfo();
    private final HardwareAbstractionLayer hal = si.getHardware();
    private final OperatingSystem os = si.getOperatingSystem();
    private final CentralProcessor cpu = hal.getProcessor();
    private final GlobalMemory mem = hal.getMemory();

    private final long sessionStart = System.currentTimeMillis();
    private long totalDataUsed = 0, prevBytesSent = 0, prevBytesRecv = 0;
    private long[][] prevTicks = cpu.getProcessorCpuLoadTicks();

    // Change-detection
    private int    lastVolume     = -999;
    private int    lastBrightness = -999;
    private String lastActiveApp  = "";
    private String lastWifiSSID   = "";
    private String lastWifiSig    = "";
    private String lastHotspot    = "";
    private String lastUpdates    = "";
    private String lastTopCpu     = "";
    private String lastBattMode   = "";
    private String lastAudioDev   = "";

    // Atomic cache
    private final AtomicInteger cachedVolume     = new AtomicInteger(-1);
    private final AtomicInteger cachedBrightness = new AtomicInteger(-1);
    private final AtomicReference<String> cachedActiveApp  = new AtomicReference<>("");
    private final AtomicReference<String> cachedAudioDev   = new AtomicReference<>("");
    private final AtomicReference<String> cachedWifiSSID   = new AtomicReference<>("");
    private final AtomicReference<String> cachedWifiSignal = new AtomicReference<>("");
    private final AtomicReference<String> cachedBattMode   = new AtomicReference<>("");
    private final AtomicReference<String> cachedUpdates    = new AtomicReference<>("Checking updates...");
    private final AtomicReference<String> cachedHotspot    = new AtomicReference<>("Checking...");
    private final AtomicReference<String> cachedTopCpu     = new AtomicReference<>("");

    // UI labels
    private JLabel lblTime, lblDate, lblOs, lblHostname, lblUptime;
    private JLabel lblCpu, lblCpuTemp, lblGpu;
    private JLabel lblRam;
    private JLabel lblBattery, lblBatteryMode;
    private JLabel lblWifi, lblWifiDetail, lblWifiStrength;
    private JLabel lblHotspot, lblDisk;
    private JLabel lblSession, lblDataUsed;
    private JLabel lblUpload, lblDownload;
    private JLabel lblBrightness, lblVolume, lblAudioDevice;
    private JLabel lblActiveApp;
    private JLabel lblHealthScore, lblPowerApps, lblUpdates;
    private JProgressBar barCpu, barRam, barBattery, barDisk,
                         barHealth, barBrightness, barVolume;
    private JTextArea areaProcesses;

    private final ScheduledExecutorService pool =
        Executors.newScheduledThreadPool(8, r -> {
            Thread t = new Thread(r, "SM-BG"); t.setDaemon(true); return t;
        });

    // Colors
    static final Color BG=new Color(10,12,20), CARD=new Color(15,18,30),
        BORDER=new Color(30,36,58), CLBL=new Color(75,85,99),
        MUTED=new Color(148,163,184), BLUE=new Color(96,165,250),
        GREEN=new Color(74,222,128), PURPLE=new Color(167,139,250),
        YELLOW=new Color(251,191,36), RED=new Color(239,68,68),
        VIOLET=new Color(196,181,253);

    public SystemMonitor() {
        setTitle("System Monitor Dashboard  |  v2.4");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1150, 820);
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG);
        setLayout(new BorderLayout(8, 8));
        add(buildHeader(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        startBgFetchers();
        startUITimer();
        setVisible(true);
    }

    // ================================================================
    // BACKGROUND FETCHERS
    // ================================================================
    private void startBgFetchers() {

        // ── Active window — 300ms (NO Add-Type, NO DLL) ───────────
        pool.scheduleAtFixedRate(() -> {
            String r = runPS(
                "Get-Process " +
                "| Where-Object { $_.MainWindowTitle -ne '' " +
                "  -and $_.MainWindowTitle -notlike '*System Monitor*' } " +
                "| Sort-Object WorkingSet -Descending " +
                "| Select-Object -First 1 " +
                "| ForEach-Object { $_.MainWindowTitle }");
            String t = r.trim();
            if (!t.isEmpty()) cachedActiveApp.set(t);
        }, 0, 300, TimeUnit.MILLISECONDS);

        // ── FIX: Volume — write PS1 script file once, reuse it ────
        // This completely avoids Add-Type re-declaration by writing the
        // script to a temp file once at startup, then calling it each time.
        // The script uses a unique class name per-session so no conflicts.
        final String volScript = System.getProperty("java.io.tmpdir")
            + "\\smvol_" + ProcessHandle.current().pid() + ".ps1";
        final String uniqueClass = "SMVol" + ProcessHandle.current().pid();

        try {
            // Write the PS1 script file once
            String scriptContent =
                "Add-Type -Name '" + uniqueClass + "' -Namespace '' " +
                "-MemberDefinition '[DllImport(\"winmm.dll\")] " +
                "public static extern int waveOutGetVolume(System.IntPtr h, out uint v);' " +
                "-EA SilentlyContinue 2>$null\r\n" +
                "$v = [uint32]0\r\n" +
                "[" + uniqueClass + "]::waveOutGetVolume([System.IntPtr]::Zero, [ref]$v) | Out-Null\r\n" +
                "[math]::Round(($v -band 0xFFFF) / 65535.0 * 100)\r\n";
            try (FileWriter fw = new FileWriter(volScript)) {
                fw.write(scriptContent);
            }
        } catch (Exception ignored) {}

        pool.scheduleAtFixedRate(() -> {
            // Call the pre-written script — Add-Type only runs once per script file
            String r = runPS("& '" + volScript + "'");
            // If script method fails, try registry
            if (r.isEmpty() || r.equals("-1")) {
                r = runPS(
                    "try { " +
                    "  $p = 'HKCU:\\Software\\Microsoft\\Multimedia\\Audio';" +
                    "  if (Test-Path $p) {" +
                    "    $v = (Get-ItemProperty $p).MasterVolume;" +
                    "    if ($v -ne $null) { [math]::Round($v / 65535 * 100) } else { -1 }" +
                    "  } else { -1 }" +
                    "} catch { -1 }");
            }
            try {
                int v = Integer.parseInt(r.trim());
                if (v >= 0 && v <= 100) cachedVolume.set(v);
            } catch (NumberFormatException ignored) {}
        }, 1, 400, TimeUnit.MILLISECONDS);

        // ── Brightness — 500ms ────────────────────────────────────
        pool.scheduleAtFixedRate(() -> {
            String r = runPS(
                "try { (Get-WmiObject -NS root/WMI " +
                "-Class WmiMonitorBrightness -EA Stop).CurrentBrightness " +
                "} catch { -1 }");
            try { cachedBrightness.set(Integer.parseInt(r.trim())); }
            catch (Exception ignored) {}
        }, 0, 500, TimeUnit.MILLISECONDS);

        // ── Audio device — 3s ─────────────────────────────────────
        pool.scheduleAtFixedRate(() -> {
            String r = runPS(
                "Get-WmiObject Win32_SoundDevice " +
                "| Where-Object { $_.StatusInfo -eq 3 } " +
                "| Select-Object -First 1 -ExpandProperty Name");
            if (!r.isBlank()) cachedAudioDev.set(r.trim());
        }, 0, 3, TimeUnit.SECONDS);

        // ── WiFi SSID + Signal — 2s ───────────────────────────────
        pool.scheduleAtFixedRate(() -> {
            String ssid = "", sig = "";
            try {
                Process p = Runtime.getRuntime().exec(
                    new String[]{"netsh","wlan","show","interfaces"});
                BufferedReader br = new BufferedReader(new InputStreamReader(
                    p.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) {
                    String t = line.trim();
                    if (t.startsWith("SSID") && !t.contains("BSSID")) {
                        int i = t.indexOf(':');
                        if (i >= 0) ssid = t.substring(i+1).trim();
                    }
                    if (t.startsWith("Signal")) {
                        int i = t.indexOf(':');
                        if (i >= 0) sig = t.substring(i+1).trim();
                    }
                }
            } catch (Exception ignored) {}
            cachedWifiSSID.set(ssid);
            cachedWifiSignal.set(sig);
        }, 0, 2, TimeUnit.SECONDS);

        // ── FIX: Battery mode from WMI — 2s ──────────────────────
        // WMI BatteryStatus codes:
        // 1=Discharging  2=AC+Full  3=FullyCharged
        // 6=Charging     7=Charging+High  8=Charging+Low  9=Charging+Critical
        pool.scheduleAtFixedRate(() -> {
            String r = runPS(
                "$b = Get-WmiObject Win32_Battery -EA SilentlyContinue; " +
                "if ($b) { $b.BatteryStatus } else { 2 }");
            try {
                int st = Integer.parseInt(r.trim());
                String mode = switch(st) {
                    case 1  -> "On Battery";
                    case 2  -> "Plugged in - AC Power";
                    case 3  -> "Fully Charged";
                    case 4  -> "On Battery (Low)";
                    case 5  -> "On Battery (Critical)";
                    case 6  -> "Charging";
                    case 7  -> "Charging";
                    case 8  -> "Charging (Low Battery)";
                    case 9  -> "Charging (Critical)";
                    case 11 -> "Partially Charged";
                    default -> "";
                };
                cachedBattMode.set(mode);
            } catch (Exception ignored) {}
        }, 0, 2, TimeUnit.SECONDS);

        // ── Top CPU apps — 3s ─────────────────────────────────────
        pool.scheduleAtFixedRate(() -> {
            String r = runPS(
                "$a = Get-Process | Sort-Object CPU -Descending " +
                "| Select-Object -First 3 -ExpandProperty Name; " +
                "$a -join ', '");
            if (!r.isBlank() && !r.contains("Exception"))
                cachedTopCpu.set(r.trim());
        }, 0, 3, TimeUnit.SECONDS);

        // ── Hotspot — 5s ──────────────────────────────────────────
        // Find hotspot adapter IP → use that subnet to filter ARP table
        // This avoids showing all devices on college/router network
        pool.scheduleAtFixedRate(() -> {
            String hotspotInfo = runPS(
                "$a = Get-NetAdapter | Where-Object { " +
                "  $_.InterfaceDescription -like '*Hosted*' -or " +
                "  $_.InterfaceDescription -like '*Virtual*Microsoft*' " +
                "} | Where-Object { $_.Status -eq 'Up' } | Select-Object -First 1; " +
                "if ($a) { " +
                "  $ip = (Get-NetIPAddress -InterfaceIndex $a.ifIndex " +
                "    -AddressFamily IPv4 -EA SilentlyContinue).IPAddress; " +
                "  'UP|' + $ip " +
                "} else { 'DOWN|' }");

            if (!hotspotInfo.startsWith("UP")) {
                cachedHotspot.set("Hotspot: OFF"); return;
            }

            String hostIp = hotspotInfo.contains("|")
                ? hotspotInfo.split("\\|")[1].trim() : "192.168.137.1";
            int lastDot = hostIp.lastIndexOf('.');
            String subnet = lastDot > 0 ? hostIp.substring(0, lastDot+1) : "192.168.137.";

            StringBuilder sb = new StringBuilder("Hotspot: ON");
            List<String> devices = new ArrayList<>();
            try {
                Process arp = Runtime.getRuntime().exec("arp -a");
                BufferedReader br = new BufferedReader(
                    new InputStreamReader(arp.getInputStream()));
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.contains("dynamic")) continue;
                    String[] cols = line.trim().split("\\s+");
                    if (cols.length > 0
                            && cols[0].startsWith(subnet)
                            && !cols[0].equals(hostIp)
                            && !cols[0].endsWith(".255")) {
                        String mac = cols.length > 1 ? " (" + cols[1] + ")" : "";
                        devices.add(cols[0] + mac);
                    }
                }
            } catch (Exception ignored) {}

            if (devices.isEmpty()) {
                sb.append("\n  No devices connected");
            } else {
                sb.append("  (").append(devices.size()).append(" device(s))");
                for (int i = 0; i < devices.size(); i++) {
                    sb.append("\n  Device ").append(i+1).append(": ").append(devices.get(i));
                }
            }
            cachedHotspot.set(sb.toString());
        }, 0, 5, TimeUnit.SECONDS);

        // ── Windows Updates — 10 min ──────────────────────────────
        pool.scheduleAtFixedRate(() -> {
            String r = runPS(
                "try { (New-Object -ComObject Microsoft.Update.Session)" +
                ".CreateUpdateSearcher().Search('IsInstalled=0').Updates.Count" +
                "} catch { 'N/A' }");
            try {
                int c = Integer.parseInt(r.trim());
                cachedUpdates.set(c == 0
                    ? "System is up to date" : c + " update(s) available");
            } catch (Exception e) {
                cachedUpdates.set("Open Windows Update to check");
            }
        }, 30, 10, TimeUnit.MINUTES);
    }

    // ================================================================
    // UI TIMER — 200ms
    // ================================================================
    private void startUITimer() {
        javax.swing.Timer t = new javax.swing.Timer(200, e -> {
            refreshTimeDate(); refreshCpu(); refreshRam(); refreshBattery();
            refreshWifi(); refreshDisk(); refreshSession(); refreshNetwork();
            refreshAudio(); refreshActiveApp(); refreshProcesses();
            refreshHealthScore(); refreshCachedLabels();
        });
        t.setCoalesce(true); t.start();
    }

    // ================================================================
    // REFRESH METHODS
    // ================================================================
    private void refreshTimeDate() {
        LocalDateTime n = LocalDateTime.now();
        lblTime.setText(n.format(DateTimeFormatter.ofPattern("hh:mm:ss a")));
        lblDate.setText(n.format(DateTimeFormatter.ofPattern("EEE, dd MMM yyyy")));
        lblOs.setText("Windows " + os.getVersionInfo().getVersion());
        lblHostname.setText(os.getNetworkParams().getHostName());
        long up = os.getSystemUptime();
        lblUptime.setText(up/3600 + "h " + (up%3600)/60 + "m");
    }

    private void refreshCpu() {
        double[] load = cpu.getProcessorCpuLoadBetweenTicks(prevTicks);
        prevTicks = cpu.getProcessorCpuLoadTicks();
        double avg = Arrays.stream(load).average().orElse(0)*100;
        lblCpu.setText(String.format("Usage: %.1f%%  |  %d cores  |  %.1f GHz",
            avg, cpu.getLogicalProcessorCount(), cpu.getMaxFreq()/1e9));
        barCpu.setValue((int)avg);
        barCpu.setForeground(avg>80?RED:avg>60?YELLOW:BLUE);
        double temp = hal.getSensors().getCpuTemperature();
        lblCpuTemp.setText(temp>0
            ? String.format("Temp: %.1fC  %s",temp,temp>85?"[HOT]":temp>70?"[WARM]":"[OK]")
            : "Temp: N/A");
        List<GraphicsCard> gpus = hal.getGraphicsCards();
        if (!gpus.isEmpty()) {
            GraphicsCard g = gpus.get(0);
            long vram = g.getVRam();
            lblGpu.setText("GPU: "+truncate(g.getName(),28)
                +"  VRAM: "+(vram>0?vram/1_073_741_824L+"GB":"Shared"));
        }
    }

    private void refreshRam() {
        long total=mem.getTotal(), used=total-mem.getAvailable();
        int pct=(int)(used*100.0/total);
        lblRam.setText(String.format("Used: %s / %s  (%d%%)",toGB(used),toGB(total),pct));
        barRam.setValue(pct); barRam.setForeground(pct>85?RED:pct>70?YELLOW:PURPLE);
    }

    // ── FIX: Battery — OSHI for % + WMI for status label ─────────
    private void refreshBattery() {
        List<PowerSource> bats = hal.getPowerSources();
        if (bats.isEmpty()) {
            lblBattery.setText("Desktop PC - No battery");
            barBattery.setValue(100); barBattery.setForeground(GREEN);
            lblBatteryMode.setText("AC Powered"); return;
        }
        PowerSource b = bats.get(0);

        // OSHI gives accurate % and plugged state
        int     pct      = (int)(b.getRemainingCapacityPercent() * 100);
        boolean onAC     = b.isPowerOnLine();
        boolean charging = b.isCharging();

        // Accurate status
        String status;
        if (onAC && pct >= 99)        status = "Fully Charged";
        else if (onAC && charging)    status = "Charging";
        else if (onAC)                status = "Plugged in";
        else                          status = "On Battery";

        // Time remaining only when discharging
        double hrs = b.getTimeRemainingEstimated() / 3600.0;
        String timeStr = (!onAC && hrs > 0 && hrs < 24)
            ? String.format("  %.1fh left", hrs) : "";

        // Show: "91%  Charging" or "63%  On Battery  2.4h left"
        lblBattery.setText(pct + "%  " + status + timeStr);
        barBattery.setValue(pct);
        barBattery.setForeground(pct < 20 ? RED : pct < 50 ? YELLOW : GREEN);

        // WMI mode detail from cache
        String mode = cachedBattMode.get();
        if (!mode.equals(lastBattMode)) {
            lastBattMode = mode;
            lblBatteryMode.setText(mode);
            lblBatteryMode.setForeground(
                mode.contains("On Battery") ? YELLOW :
                mode.contains("Critical")   ? RED    : GREEN);
        }
    }

    private void refreshWifi() {
        for (NetworkIF n : hal.getNetworkIFs()) {
            n.updateAttributes();
            String dn=n.getDisplayName(), lo=dn.toLowerCase();
            if (lo.contains("virtual")||lo.contains("loopback")
                    ||lo.contains("bluetooth")||lo.contains("hosted")) continue;
            if (lo.contains("wi-fi")||lo.contains("wifi")
                    ||lo.contains("wlan")||lo.contains("wireless")) {
                String ip=n.getIPv4addr().length>0?n.getIPv4addr()[0]:"N/A";
                String mac=n.getMacaddr();
                long sp=n.getSpeed();
                String spStr=sp>0?(sp>=1_000_000_000?sp/1_000_000_000+"Gbps":sp/1_000_000+"Mbps"):"—";
                String ssid=cachedWifiSSID.get(), sig=cachedWifiSignal.get();
                if (!ssid.equals(lastWifiSSID)||!sig.equals(lastWifiSig)) {
                    lastWifiSSID=ssid; lastWifiSig=sig;
                    lblWifi.setText("Connected: "+(ssid.isEmpty()?dn:ssid));
                    lblWifiStrength.setText(sigLabel(sig)+" "+sig+"  |  Speed: "+spStr);
                    lblWifiDetail.setText("IP: "+ip+"  |  MAC: "+mac);
                }
                return;
            }
        }
        if (!"NC".equals(lastWifiSSID)) {
            lastWifiSSID="NC"; lblWifi.setText("WiFi: Not connected");
            lblWifiStrength.setText(""); lblWifiDetail.setText("");
        }
    }

    private void refreshDisk() {
        long total=0, usable=0;
        for (OSFileStore f : os.getFileSystem().getFileStores()) {
            total+=f.getTotalSpace(); usable+=f.getUsableSpace();
        }
        long used=total-usable; int pct=total>0?(int)(used*100.0/total):0;
        lblDisk.setText(String.format("Used: %s / %s  |  Free: %s  (%d%%)",
            toGB(used),toGB(total),toGB(usable),pct));
        barDisk.setValue(pct); barDisk.setForeground(pct>90?RED:pct>75?YELLOW:GREEN);
    }

    private void refreshSession() {
        long e=System.currentTimeMillis()-sessionStart;
        lblSession.setText(String.format("Session: %02dh %02dm %02ds",
            e/3_600_000,(e%3_600_000)/60_000,(e%60_000)/1000));
        lblDataUsed.setText("Data used: "+toMB(totalDataUsed));
    }

    private void refreshNetwork() {
        long sent=0, recv=0;
        for (NetworkIF n : hal.getNetworkIFs()) {
            n.updateAttributes(); sent+=n.getBytesSent(); recv+=n.getBytesRecv();
        }
        long up=Math.max(0,sent-prevBytesSent), dn=Math.max(0,recv-prevBytesRecv);
        totalDataUsed+=(up+dn); prevBytesSent=sent; prevBytesRecv=recv;
        lblUpload.setText("Upload:   "+toKB(up)+"/s");
        lblDownload.setText("Download: "+toKB(dn)+"/s");
    }

    // ── FIX: Volume reads from atomic cache updated every 400ms ───
    private void refreshAudio() {
        int vol = cachedVolume.get();
        if (vol >= 0 && vol != lastVolume) {
            lastVolume = vol;
            lblVolume.setText("Volume: " + vol + "%");
            barVolume.setValue(vol);
            barVolume.setForeground(vol==0?RED:vol<30?YELLOW:BLUE);
        }
        int bright = cachedBrightness.get();
        if (bright != lastBrightness) {
            lastBrightness = bright;
            if (bright >= 0) {
                lblBrightness.setText("Brightness: " + bright + "%");
                barBrightness.setValue(bright);
            } else {
                lblBrightness.setText("Brightness: External monitor");
                barBrightness.setValue(0);
            }
        }
        String dev = cachedAudioDev.get();
        if (!dev.equals(lastAudioDev) && !dev.isBlank()) {
            lastAudioDev = dev;
            String dl = dev.toLowerCase();
            String icon = dl.contains("bluetooth") ? "[BT]"
                : dl.contains("headphone")||dl.contains("headset") ? "[HP]"
                : dl.contains("hdmi") ? "[HDMI]" : "[SPK]";
            lblAudioDevice.setText(icon + " " + truncate(dev, 36));
        }
    }

    private void refreshActiveApp() {
        String app = cachedActiveApp.get();
        if (app.isEmpty() || app.equals(lastActiveApp)) return;
        lastActiveApp = app;
        String d = app
            .replace(" - Google Chrome",           " · Chrome")
            .replace(" - Mozilla Firefox",         " · Firefox")
            .replace(" - Microsoft Edge",          " · Edge")
            .replace("Visual Studio Code",         "VS Code")
            .replace(" - Microsoft Visual Studio", " · VS")
            .replace("Microsoft Teams",            "Teams")
            .replace(" - Notepad",                 " · Notepad");
        lblActiveApp.setText(
            "<html><body style='width:210px;color:rgb(196,181,253)'>"
            + escHtml(truncate(d, 120)) + "</body></html>");
    }

    private void refreshProcesses() {
        List<OSProcess> procs = os.getProcesses(null, null, 10);
        procs.sort((a,b)->Long.compare(b.getResidentSetSize(),a.getResidentSetSize()));
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (OSProcess pr : procs) {
            long mb=pr.getResidentSetSize()/(1024*1024);
            if (mb<1) continue;
            sb.append(String.format("%-20s %5d MB%n",truncate(pr.getName(),20),mb));
            if (++shown>=7) break;
        }
        areaProcesses.setText(sb.toString());
    }

    private void refreshHealthScore() {
        int score=100;
        double[] load=cpu.getProcessorCpuLoadBetweenTicks(prevTicks);
        double cp=Arrays.stream(load).average().orElse(0)*100;
        if (cp>80) score-=25; else if (cp>60) score-=12;
        long used=mem.getTotal()-mem.getAvailable();
        int rp=(int)(used*100.0/mem.getTotal());
        if (rp>85) score-=25; else if (rp>70) score-=12;
        long t=0,u=0;
        for (OSFileStore f:os.getFileSystem().getFileStores()){t+=f.getTotalSpace();u+=f.getUsableSpace();}
        int dp=t>0?(int)((t-u)*100.0/t):0;
        if (dp>90) score-=20; else if (dp>80) score-=10;
        score=Math.max(0,score);
        barHealth.setValue(score); lblHealthScore.setText("PC Health: "+score+"/100");
        barHealth.setForeground(score>70?GREEN:score>40?YELLOW:RED);
    }

    private void refreshCachedLabels() {
        String upd=cachedUpdates.get();
        if (!upd.equals(lastUpdates)) {
            lastUpdates=upd; lblUpdates.setText(upd);
            lblUpdates.setForeground(upd.contains("available")?YELLOW
                :upd.contains("up to date")?GREEN:MUTED);
        }
        String hs=cachedHotspot.get();
        if (!hs.equals(lastHotspot)) {
            lastHotspot=hs;
            lblHotspot.setText("<html>"
                +hs.replace("&","&amp;").replace("<","&lt;").replace("\n","<br>")
                +"</html>");
            lblHotspot.setForeground(hs.contains("ON")?GREEN:MUTED);
        }
        String tc=cachedTopCpu.get();
        if (!tc.equals(lastTopCpu)&&!tc.isBlank()) {
            lastTopCpu=tc; lblPowerApps.setText("Top CPU: "+truncate(tc,70));
        }
    }

    // ================================================================
    // BUILD UI
    // ================================================================
    private JPanel buildHeader() {
        JPanel p=dp(new FlowLayout(FlowLayout.LEFT,16,10));
        p.setBorder(new MatteBorder(0,0,1,0,BORDER));
        // No weather label — removed
        lblTime=lbl("--:--:--",24,BLUE); lblDate=lbl("",13,MUTED);
        lblOs=lbl("",12,MUTED); lblHostname=lbl("",12,MUTED);
        lblUptime=lbl("",12,MUTED);
        p.add(lblTime);p.add(sep());p.add(lblDate);p.add(sep());
        p.add(lblOs);p.add(sep());p.add(lblHostname);p.add(sep());
        p.add(lbl("Uptime:",11,CLBL));p.add(lblUptime);
        return p;
    }

    private JScrollPane buildCenter() {
        JPanel g=dp(new GridLayout(4,3,8,8));
        g.setBorder(new EmptyBorder(8,8,8,8));
        g.add(buildCpuCard());  g.add(buildRamCard());  g.add(buildBattCard());
        g.add(buildWifiCard()); g.add(buildDiskCard()); g.add(buildSessCard());
        g.add(buildNetCard());  g.add(buildAudioCard());g.add(buildAppCard());
        g.add(buildProcCard()); g.add(buildUpdCard());  g.add(buildHotCard());
        JScrollPane sp=new JScrollPane(g);
        sp.setBorder(null); sp.getViewport().setBackground(BG); return sp;
    }

    private JPanel buildFooter() {
        JPanel p=dp(new FlowLayout(FlowLayout.LEFT,16,8));
        p.setBorder(new MatteBorder(1,0,0,0,BORDER));
        lblHealthScore=lbl("PC Health: --",13,GREEN);
        barHealth=pb(GREEN); barHealth.setPreferredSize(new Dimension(160,12));
        lblPowerApps=lbl("",12,YELLOW);
        p.add(lblHealthScore);p.add(barHealth);p.add(sep());p.add(lblPowerApps);
        return p;
    }

    private JPanel buildCpuCard() {
        JPanel c=card("CPU");
        lblCpu=lbl("--",13,Color.WHITE); barCpu=pb(BLUE);
        lblCpuTemp=lbl("Temp: --",12,MUTED); lblGpu=lbl("GPU: --",12,MUTED);
        c.add(lblCpu);c.add(vs(4));c.add(barCpu);
        c.add(vs(5));c.add(lblCpuTemp);c.add(vs(3));c.add(lblGpu);
        return c;
    }
    private JPanel buildRamCard() {
        JPanel c=card("RAM"); lblRam=lbl("--",13,Color.WHITE); barRam=pb(PURPLE);
        c.add(lblRam);c.add(vs(5));c.add(barRam); return c;
    }
    private JPanel buildBattCard() {
        JPanel c=card("Battery");
        lblBattery=lbl("--",13,Color.WHITE); barBattery=pb(GREEN);
        lblBatteryMode=lbl("",12,YELLOW);
        c.add(lblBattery);c.add(vs(4));c.add(barBattery);
        c.add(vs(5));c.add(lblBatteryMode); return c;
    }
    private JPanel buildWifiCard() {
        JPanel c=card("WiFi / Network");
        lblWifi=lbl("--",13,Color.WHITE);
        lblWifiStrength=lbl("",11,GREEN); lblWifiDetail=lbl("",11,MUTED);
        c.add(lblWifi);c.add(vs(3));c.add(lblWifiStrength);
        c.add(vs(3));c.add(lblWifiDetail); return c;
    }
    private JPanel buildDiskCard() {
        JPanel c=card("Disk Storage"); lblDisk=lbl("--",13,Color.WHITE); barDisk=pb(YELLOW);
        c.add(lblDisk);c.add(vs(5));c.add(barDisk); return c;
    }
    private JPanel buildSessCard() {
        JPanel c=card("Session");
        lblSession=lbl("Session: --",13,Color.WHITE); lblDataUsed=lbl("Data: --",12,MUTED);
        c.add(lblSession);c.add(vs(4));c.add(lblDataUsed); return c;
    }
    private JPanel buildNetCard() {
        JPanel c=card("Network Speed");
        lblUpload=lbl("Upload:   --",13,GREEN); lblDownload=lbl("Download: --",13,BLUE);
        c.add(lblUpload);c.add(vs(4));c.add(lblDownload); return c;
    }
    private JPanel buildAudioCard() {
        JPanel c=card("Display & Audio");
        lblBrightness=lbl("Brightness: --",13,Color.WHITE); barBrightness=pb(YELLOW);
        lblVolume=lbl("Volume: --",13,Color.WHITE); barVolume=pb(BLUE);
        lblAudioDevice=lbl("Device: --",11,MUTED);
        c.add(lblBrightness);c.add(vs(3));c.add(barBrightness);
        c.add(vs(6));c.add(lblVolume);c.add(vs(3));c.add(barVolume);
        c.add(vs(4));c.add(lblAudioDevice); return c;
    }
    private JPanel buildAppCard() {
        JPanel c=card("Currently Active Window");
        lblActiveApp=lbl("--",12,VIOLET); c.add(lblActiveApp); return c;
    }
    private JPanel buildProcCard() {
        JPanel c=card("Top Processes (RAM)");
        areaProcesses=new JTextArea();
        areaProcesses.setFont(new Font("Monospaced",Font.PLAIN,11));
        areaProcesses.setBackground(CARD); areaProcesses.setForeground(MUTED);
        areaProcesses.setEditable(false); areaProcesses.setBorder(new EmptyBorder(4,4,4,4));
        JScrollPane sp=new JScrollPane(areaProcesses);
        sp.setBorder(null); sp.getViewport().setBackground(CARD);
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.add(sp); return c;
    }
    private JPanel buildUpdCard() {
        JPanel c=card("System Updates");
        lblUpdates=lbl("Checking...",12,YELLOW); c.add(lblUpdates); return c;
    }
    private JPanel buildHotCard() {
        JPanel c=card("Hotspot / Connected Devices");
        lblHotspot=lbl("Checking...",12,MUTED); c.add(lblHotspot); return c;
    }

    // ================================================================
    // UI FACTORIES
    // ================================================================
    private JPanel card(String title) {
        JPanel p=new JPanel(); p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));
        p.setBackground(CARD);
        p.setBorder(BorderFactory.createCompoundBorder(
            new LineBorder(BORDER,1,true),new EmptyBorder(10,12,10,12)));
        JLabel l=new JLabel(title.toUpperCase());
        l.setFont(new Font("Arial",Font.BOLD,10)); l.setForeground(CLBL);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(l); p.add(Box.createVerticalStrut(7)); return p;
    }
    private JPanel dp(LayoutManager lm){JPanel p=new JPanel(lm);p.setBackground(BG);return p;}
    private JLabel lbl(String t,int sz,Color c){
        JLabel l=new JLabel(t); l.setFont(new Font("Arial",Font.PLAIN,sz));
        l.setForeground(c); l.setAlignmentX(Component.LEFT_ALIGNMENT); return l;
    }
    private JProgressBar pb(Color c){
        JProgressBar b=new JProgressBar(0,100); b.setForeground(c);
        b.setBackground(new Color(25,30,48)); b.setBorderPainted(false);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE,8));
        b.setAlignmentX(Component.LEFT_ALIGNMENT); return b;
    }
    private JSeparator sep(){
        JSeparator s=new JSeparator(SwingConstants.VERTICAL);
        s.setPreferredSize(new Dimension(1,16)); s.setForeground(BORDER); return s;
    }
    private Component vs(int h){return Box.createVerticalStrut(h);}

    // ================================================================
    // UTILITIES
    // ================================================================
    private String runPS(String cmd) {
        try {
            ProcessBuilder pb=new ProcessBuilder(
                "powershell","-NonInteractive","-NoProfile",
                "-WindowStyle","Hidden","-command",cmd);
            pb.redirectErrorStream(true);
            Process p=pb.start();
            String out=new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
            p.waitFor(4,TimeUnit.SECONDS);
            if (out.contains("CategoryInfo")||out.contains("FullyQualifiedErrorId")
                    ||out.contains("is not recognized"))
                return "";
            return out;
        } catch (Exception e){return "";}
    }

    private String extractJson(String json,String key) {
        try {
            int i=json.indexOf("\""+key+"\":");
            if (i<0) return "";
            String sub=json.substring(i+key.length()+3).trim();
            if (sub.startsWith("\"")) return sub.substring(1,sub.indexOf("\"",1));
            int end=sub.indexOf(","); if (end<0) end=sub.indexOf("}");
            return end>0?sub.substring(0,end).trim():sub.trim();
        } catch (Exception e){return "";}
    }

    private String sigLabel(String sig) {
        try {
            int s=Integer.parseInt(sig.replace("%","").trim());
            if (s>=80) return "[STRONG]"; if (s>=60) return "[GOOD]";
            if (s>=40) return "[FAIR]"; return "[WEAK]";
        } catch (Exception e){return "";}
    }

    private String toGB(long b){return String.format("%.1f GB",b/1_073_741_824.0);}
    private String toMB(long b){return String.format("%.1f MB",b/1_048_576.0);}
    private String toKB(long b){return String.format("%.1f KB",b/1024.0);}
    private String truncate(String s,int max){
        return s==null?"":s.length()>max?s.substring(0,max-1)+"...":s;
    }
    private String escHtml(String s){
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }

    public static void main(String[] args) {
        try{UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());}
        catch(Exception ignored){}
        SwingUtilities.invokeLater(SystemMonitor::new);
    }
}