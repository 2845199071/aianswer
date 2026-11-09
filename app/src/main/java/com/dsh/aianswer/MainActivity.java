package com.dsh.aianswer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends Activity {

    private static final String TAG = "AIQ";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean downloading = false;
    private volatile boolean testing = false;
    private volatile boolean loadingModel = false;
    private boolean keyShown = false;
    private boolean binding = false;
    private CloudSolver.Provider curProvider;

    // ---- 主界面 ----
    private TextView tvOnlineSub, tvOnlineState, tvLocalSub, tvLocalState;
    private Switch swAuto;
    // 以下控件都在二级弹窗里，打开时绑定、关闭时置空
    private TextView tvOut, tvTh, tvBench, tvSession;
    private TextView tvSessionLine;
    private Switch swClick, swVibrate, swDual;
    private SeekBar sbTh;
    private EditText etQ;
    private Button btnBench;
    // 卡片式主界面（v6）
    private TextView tvHeroTitle, tvHeroSub;
    private TextView tvBallTitle, tvBallSub, tvBallIcon;
    private View cardBall;
    private TextView pillAcc, pillOverlay;
    // 设置弹窗里的版本行
    private TextView tvSettingsVersion;

    // ---- 在线弹窗 ----
    private Switch swCloudOn, swDualCloud;
    private LinearLayout boxProviders;
    private TextView segLocal, segCloud, tvOrderHint, tvProviderCount, tvExpand;
    private EditText etKey, etBase, etModel;
    private TextView tvToggleKey, tvKeyNote, tvOfficial, tvCloudState, btnTest, btnSave;
    private View boxCustom;
    private boolean providersExpanded = false;

    // ---- 本地弹窗 ----
    private Switch swLocalOn;
    private TextView tvModelState;
    private LinearLayout boxModels;
    private ProgressBar pb;
    // 注意：dialog_local.xml 里 btnReload 是 TextView，千万别声明成 Button
    // （Button 强转 TextView 会抛 ClassCastException，点本地模型直接闪退）
    private TextView btnReload;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        tvOnlineSub   = findViewById(R.id.tvOnlineSub);
        tvOnlineState = findViewById(R.id.tvOnlineState);
        tvLocalSub    = findViewById(R.id.tvLocalSub);
        tvLocalState  = findViewById(R.id.tvLocalState);
        tvSessionLine = findViewById(R.id.tvSessionLine);
        swAuto        = findViewById(R.id.swAuto);

        tvHeroTitle   = findViewById(R.id.tvHeroTitle);
        tvHeroSub     = findViewById(R.id.tvHeroSub);
        tvBallTitle   = findViewById(R.id.tvBallTitle);
        tvBallSub     = findViewById(R.id.tvBallSub);
        tvBallIcon    = findViewById(R.id.tvBallIcon);
        cardBall      = findViewById(R.id.btnShowBall);
        pillAcc       = findViewById(R.id.rowAcc);
        pillOverlay   = findViewById(R.id.rowOverlay);

        // 从没选过档位就按手机内存推荐；本地已下好哪个就用哪个
        if (!Prefs.hasSpecChosen(this)) {
            Prefs.setSpecId(this, ModelManager.recommend(this).id);
        }
        ModelManager.Spec detected = ModelManager.pickReady(this, Prefs.specId(this));
        if (detected != null) Prefs.setSpecId(this, detected.id);

        binding = true;
        swAuto.setChecked(Prefs.autoAnswer(this));
        binding = false;

        findViewById(R.id.cardOnline).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showOnlineDialog(); }
        });
        findViewById(R.id.cardLocal).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showLocalDialog(); }
        });

        findViewById(R.id.rowAcc).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    Toast.makeText(MainActivity.this, "找到「AI 答题」并打开", Toast.LENGTH_LONG).show();
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "打不开无障碍设置", Toast.LENGTH_SHORT).show();
                }
            }
        });
        // 胶囊 = 授权状态：没授权就去授权，已授权则切换显示/收起
        findViewById(R.id.rowOverlay).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!Settings.canDrawOverlays(MainActivity.this)) {
                    openOverlaySettings();
                    return;
                }
                if (Prefs.overlayHidden(MainActivity.this)) showBall(); else hideBall();
            }
        });
        // 蓝色主卡 = 显示/收起 一个按钮切换
        cardBall.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (ballVisible()) hideBall(); else showBall();
            }
        });
        findViewById(R.id.btnDiag).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showDiagnosis(); }
        });

        swAuto.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton v, boolean on) {
                if (binding) return;
                Prefs.setAutoAnswer(MainActivity.this, on);
                QService.refreshOverlay();
            }
        });

        // 二级弹窗入口
        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showSettingsDialog(); }
        });
        findViewById(R.id.btnOpenRecord).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showRecordDialog(); }
        });
        findViewById(R.id.btnOpenSelftest).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showSelfTestDialog(); }
        });
        findViewById(R.id.btnCopyQuick).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { copyAnswers(); }
        });

        refresh();

        if (Prefs.localOn(this) && !AnswerEngine.get().ready()
                && ModelManager.canLoad(this, Prefs.spec(this))) {
            loadModel();
        }
    }

    /**
     * 状态轮询：无障碍服务是系统异步绑定的，从设置页回来后 or 刚开机时
     * serviceAlive() 会晚几百毫秒才变 true。只刷状态，不动列表/输入框，
     * 避免弹窗里的模型列表被反复重建。
     */
    private final Runnable statusTick = new Runnable() {
        public void run() {
            updatePermRows();
            updateBallCard();
            updateHero();
            ui.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        ui.removeCallbacks(statusTick);
        ui.post(statusTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(statusTick);
        if (etKey != null) saveProviderFields(curProvider);   // 弹窗还开着就落盘
    }

    // ================= 主界面卡片 =================

    /** 无障碍服务是否出现在系统的启用列表里（比只看 serviceAlive 可靠：服务可能被系统暂时回收）。 */
    private boolean accEnabled() {
        try {
            String list = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null) return false;
            return list.contains(getPackageName() + "/");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 胶囊标签：浅色底 + 同色系深字，短状态一律走这里。 */
    /**
     * 胶囊标签唯一实现：浅底 + 同色系深字，全圆角。
     * 中性态（bg 传 bg_field）自动换成"白底 + 细描边"，否则在白卡/页面底上都糊成一片。
     */
    private void setPill(TextView pill, String text, int bgRes, int fgRes) {
        if (pill == null) return;
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(100));
        if (bgRes == R.color.bg_field) {
            g.setColor(clr(R.color.bg_card));
            g.setStroke(dp(1), clr(R.color.stroke));
        } else {
            g.setColor(clr(bgRes));
        }
        pill.setBackground(g);
        pill.setText(text);
        pill.setTextColor(clr(fgRes));
    }

    private void updatePermRows() {
        // 无障碍：开启但没连上也算问题——那种状态下悬浮窗和读屏都不工作
        boolean accOn   = accEnabled();
        boolean accLive = QService.serviceAlive();
        // 服务活着就一定能用——某些系统（MIUI）重装后会把它从设置列表里摘掉，
        // 但进程仍被系统绑着。这时以「实际连接」为准，否则会误报「未开启」误导用户去重开。
        if (accLive) {
            setPill(pillAcc, accOn ? "读屏 已开启" : "读屏 运行中", R.color.success_bg, R.color.success);
        } else if (accOn) {
            setPill(pillAcc, "读屏 未连接", R.color.warning_bg, R.color.warning);
        } else {
            setPill(pillAcc, "读屏 未开启", R.color.danger_bg, R.color.danger);
        }

        if (!Settings.canDrawOverlays(this)) {
            setPill(pillOverlay, "悬浮窗 未授权", R.color.danger_bg, R.color.danger);
        } else if (Prefs.overlayHidden(this)) {
            setPill(pillOverlay, "悬浮窗 已收起", R.color.bg_field, R.color.text_hint);
        } else {
            setPill(pillOverlay, "悬浮窗 已授权", R.color.success_bg, R.color.success);
        }
    }

    // ================= 悬浮球：主行动卡 =================

    private boolean ballVisible() {
        return Settings.canDrawOverlays(this)
                && !Prefs.overlayHidden(this)
                && QService.serviceAlive();
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            Toast.makeText(this, "打开「显示在其他应用上层」后回来", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "请手动授予悬浮窗权限", Toast.LENGTH_SHORT).show();
        }
    }

    /** 显示悬浮球：先补齐权限 → 无障碍服务 → 再显示。 */
    private void showBall() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings();
            return;
        }
        if (!QService.serviceAlive()) {
            Toast.makeText(this, "无障碍服务没在运行，先开启「读屏」", Toast.LENGTH_SHORT).show();
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable ignored) {}
            return;
        }
        Prefs.setOverlayHidden(this, false);
        QService.showOverlay();
        Toast.makeText(this, "悬浮球已显示，回到刷题页面即可", Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void hideBall() {
        if (Prefs.overlayHidden(this)) {
            Toast.makeText(this, "悬浮球现在是收起状态", Toast.LENGTH_SHORT).show();
            return;
        }
        Prefs.setOverlayHidden(this, true);
        QService.hideOverlay();
        refresh();
    }

    /** 主卡文案随状态变：已显示走成功绿（点击=收起），未显示走品牌蓝（点击=显示）。 */
    private void updateBallCard() {
        if (cardBall == null) return;
        if (ballVisible()) {
            cardBall.setBackgroundResource(R.drawable.hero_tap_done);
            tvBallTitle.setText("收起悬浮球");
            tvBallSub.setText("悬浮球正在屏幕上，点这里收起");
            tvBallIcon.setText("答");
            tvBallIcon.setTextColor(clr(R.color.success));
        } else {
            cardBall.setBackgroundResource(R.drawable.hero_tap);
            tvBallTitle.setText("显示悬浮球");
            tvBallSub.setText(Settings.canDrawOverlays(this)
                    ? (QService.serviceAlive() ? "点一下就在屏幕上出现，可拖动换位置"
                                               : "无障碍没在运行，点这里去开启")
                    : "没给权限会先引导授权，给过就直接显示");
            tvBallIcon.setText("答");
            tvBallIcon.setTextColor(clr(R.color.primary));
        }
    }

    /** 顶部状态卡：当前方案 + 用量摘要。 */
    private void updateHero() {
        if (tvHeroTitle == null) return;
        ModelManager.Spec s = Prefs.spec(this);
        boolean localReady = AnswerEngine.get().ready();
        boolean localOn    = Prefs.localOn(this);
        CloudSolver.Provider p = Prefs.provider(this);
        boolean cloudOn    = Prefs.cloudOn(this) && Prefs.cloudReady(this, p);

        boolean hasLocal   = ModelManager.canLoad(this, s);
        String onlineName  = p.label.replaceAll("（.*$", "");

        // 大标题要回答"现在到底能不能答"，所以有模型就报名字，别笼统说"没配置"
        StringBuilder t = new StringBuilder();
        if (localOn && localReady) {
            t.append(s.name);
        } else if (cloudOn) {
            t.append("在线 · ").append(onlineName);
        } else if (localOn && hasLocal) {
            t.append(s.name).append(" · 待载入");
        } else if (hasLocal) {
            t.append(s.name).append(" · 未启用");
        } else if (Prefs.cloudReady(this, p)) {
            t.append(onlineName).append(" · 未启用");
        } else {
            t.append("还没配置解题方式");
        }
        tvHeroTitle.setText(t.toString());

        Record.Stats st = Record.statsSnapshot();
        String sub = "题库记住 " + Record.bankSize(this) + " 题";
        if (st.total > 0) sub += " · 本场 " + st.total + " 题";
        else sub += " · 本场还没开始";
        tvHeroSub.setText(sub);
    }

    private void refresh() {
        updateOnlineCard();
        updateLocalCard();
        updateHero();
        updateBallCard();
        // 弹窗开着时同步内部状态
        if (tvCloudState != null) { updateCloudStateText(); }
        if (tvKeyNote != null)    { updateKeyNote(); }
        if (boxModels != null)    { renderModels(); }
        if (tvModelState != null) { updateModelStateText(); }
        if (btnReload != null) {
            btnReload.setEnabled(!loadingModel);
            btnReload.setText(loadingModel ? "载入中…" : "重新加载");
        }
        Record.Stats st = Record.statsSnapshot();
        int bank = Record.bankSize(this);

        // 主界面：一行摘要
        if (tvSessionLine != null) {
            if (st.total == 0) {
                tvSessionLine.setText("本场还没有记录");
                tvSessionLine.setTextColor(clr(R.color.text_hint));
            } else {
                String avg = st.avgMs() < 1000 ? st.avgMs() + "ms"
                        : String.format(java.util.Locale.US, "%.1fs", st.avgMs() / 1000.0);
                tvSessionLine.setText("本场 " + st.total + " 题 · 平均 " + avg + " · 题库 " + bank);
                tvSessionLine.setTextColor(clr(R.color.text_secondary));
            }
        }

        // 记录弹窗：完整统计
        if (tvSession != null) {
            if (st.total == 0) {
                tvSession.setText("还没有记录\n刷几道题后，这里会显示题数、平均耗时和来源分布");
                tvSession.setTextColor(clr(R.color.text_hint));
            } else {
                String line = st.total + " 题 · 平均 " + (st.avgMs() < 1000
                        ? st.avgMs() + "ms" : String.format(java.util.Locale.US, "%.1fs", st.avgMs() / 1000.0));
                StringBuilder sb2 = new StringBuilder(line);
                sb2.append("\n本地 ").append(st.local).append(" · 在线 ").append(st.cloud)
                   .append(" · 题库 ").append(st.bank);
                if (st.low > 0) sb2.append(" · 低置信 ").append(st.low);
                sb2.append("\n题库已记住 ").append(bank).append(" 道题");
                tvSession.setText(sb2.toString());
                tvSession.setTextColor(clr(R.color.text_body));
            }
        }
        updatePermRows();
    }

    private void badge(TextView v, String text, int bg, int fg) {
        setPill(v, text, bg, fg);
    }

    private void updateOnlineCard() {
        CloudSolver.Provider p = Prefs.provider(this);
        boolean on = Prefs.cloudOn(this);
        boolean ready = Prefs.cloudReady(this, p);

        int calls = Prefs.cloudCalls(this);
        String used = calls > 0 ? " · 累计用 " + calls + " 次" : "";
        tvOnlineSub.setText(p.label.replaceAll("（.*$", "") + (ready ? used : " · 未填 Key"));
        if (on && ready) {
            badge(tvOnlineState, "已启用", R.color.success_bg, R.color.success);
        } else if (!ready) {
            badge(tvOnlineState, "未配置", R.color.bg_field, R.color.text_hint);
        } else {
            badge(tvOnlineState, "已关闭", R.color.bg_field, R.color.text_hint);
        }
    }

    private void updateLocalCard() {
        ModelManager.Spec s = Prefs.spec(this);
        boolean on = Prefs.localOn(this);
        boolean ready = AnswerEngine.get().ready();
        boolean has = ModelManager.canLoad(this, s);
        long len = ModelManager.fileOf(this, s).length();

        String sub;
        if (ready)      sub = s.name + " · 已载入 " + mb(len);
        else if (has)   sub = s.name + " · 已下载 " + mb(len) + "，未载入";
        else            sub = "未下载 · 全程离线，不耗流量";
        tvLocalSub.setText(sub);

        if (!on)            badge(tvLocalState, "已关闭", R.color.bg_field, R.color.text_hint);
        else if (ready)     badge(tvLocalState, "已启用", R.color.success_bg, R.color.success);
        else if (has)       badge(tvLocalState, "待载入", R.color.warning_bg, R.color.warning);
        else                badge(tvLocalState, "未下载", R.color.bg_field, R.color.text_hint);
    }

    // ================= 在线配置弹窗 =================

    private void showOnlineDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_online, null);
        swCloudOn      = v.findViewById(R.id.swCloudOn);
        swDualCloud    = v.findViewById(R.id.swDualCloud);
        boxProviders   = v.findViewById(R.id.boxProviders);
        segLocal       = v.findViewById(R.id.segLocal);
        segCloud       = v.findViewById(R.id.segCloud);
        tvOrderHint    = v.findViewById(R.id.tvOrderHint);
        tvProviderCount= v.findViewById(R.id.tvProviderCount);
        tvExpand       = v.findViewById(R.id.tvExpand);
        etKey          = v.findViewById(R.id.etKey);
        etBase         = v.findViewById(R.id.etBase);
        etModel        = v.findViewById(R.id.etModel);
        boxCustom      = v.findViewById(R.id.boxCustom);
        tvToggleKey    = v.findViewById(R.id.tvToggleKey);
        tvKeyNote      = v.findViewById(R.id.tvKeyNote);
        tvOfficial     = v.findViewById(R.id.tvOfficial);
        tvCloudState   = v.findViewById(R.id.tvCloudState);
        btnTest        = v.findViewById(R.id.btnTest);
        btnSave        = v.findViewById(R.id.btnSave);

        binding = true;
        swCloudOn.setChecked(Prefs.cloudOn(this));
        swDualCloud.setChecked(Prefs.dualCloudAllowed(this));
        curProvider = Prefs.provider(this);
        binding = false;

        applyOrderSeg();
        loadProviderFields(curProvider);
        renderProviders();
        updateCloudStateText();

        swCloudOn.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton c, boolean on) {
                if (binding) return;
                Prefs.setCloudOn(MainActivity.this, on);
                updateOnlineCard();
                updateCloudStateText();
                if (on && !Prefs.cloudReady(MainActivity.this, Prefs.provider(MainActivity.this))) {
                    Toast.makeText(MainActivity.this, "还没填 Key，先点「测试连接」确认", Toast.LENGTH_LONG).show();
                }
            }
        });

        swDualCloud.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton c, boolean on) {
                if (binding) return;
                Prefs.setDualCloudAllowed(MainActivity.this, on);
                if (on && !Prefs.provider(MainActivity.this).free) {
                    Toast.makeText(MainActivity.this,
                            "当前是付费渠道，复核会产生费用", Toast.LENGTH_LONG).show();
                }
            }
        });

        segLocal.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { setOrder(false); }
        });
        segCloud.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { setOrder(true); }
        });

        tvExpand.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                providersExpanded = !providersExpanded;
                renderProviders();
            }
        });

        tvToggleKey.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { keyShown = !keyShown; applyKeyMask(); }
        });
        tvOfficial.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { openOfficial(); }
        });
        btnTest.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { testCloud(); }
        });

        AlertDialog d = new AlertDialog.Builder(this, R.style.WhiteDialog)
                .setView(v)
                .create();
        btnSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { d.dismiss(); }
        });
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface dd) {
                saveProviderFields(curProvider);
                etKey = null; etBase = null; etModel = null;
                swCloudOn = null; swDualCloud = null; boxProviders = null;
                segLocal = null; segCloud = null; tvOrderHint = null;
                tvProviderCount = null; tvExpand = null;
                tvCloudState = null; tvKeyNote = null; boxCustom = null;
                btnTest = null; btnSave = null; tvOfficial = null; tvToggleKey = null;
                QService.resumeOverlayAfterDialog();     // 关掉弹窗把悬浮窗放回来
                refresh();
            }
        });
        QService.pauseOverlayForDialog();                // 弹窗期间先把悬浮窗收走，别压着内容
        showSheet(d);
        if (d.getWindow() != null) {
            d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    /** 切换优先顺序（分段控件）。 */
    private void setOrder(boolean cloudFirst) {
        if (Prefs.preferCloud(this) == cloudFirst) return;
        Prefs.setPreferCloud(this, cloudFirst);
        CloudSolver.resetBreaker();     // 换了顺序就清掉熔断，新设置立刻生效
        applyOrderSeg();
        updateOnlineCard();
        updateCloudStateText();
    }

    private void applyOrderSeg() {
        if (segLocal == null || segCloud == null) return;
        boolean cloud = Prefs.preferCloud(this);
        segCloud.setBackgroundResource(cloud ? R.drawable.seg_on : R.drawable.seg_off);
        segLocal.setBackgroundResource(cloud ? R.drawable.seg_off : R.drawable.seg_on);
        segCloud.setTextColor(cloud ? 0xFFFFFFFF : clr(R.color.text_secondary));
        segLocal.setTextColor(cloud ? clr(R.color.text_secondary) : 0xFFFFFFFF);
        if (tvOrderHint != null) {
            tvOrderHint.setText(cloud
                    ? "更准，但免费渠道可能排队 2~5 秒"
                    : "1 秒出答案，不产生费用");
        }
    }

    /**
     * 渠道列表：卡片式，默认只显示前 3 个（当前选中的始终可见），其余收起。
     * 7 个全列出来会把弹窗撑成两屏，用户第一眼找不到已选的。
     */
    private void renderProviders() {
        if (boxProviders == null) return;
        boxProviders.removeAllViews();

        int free = 0, paid = 0, total = 0;
        for (CloudSolver.Provider p : CloudSolver.ALL) {
            if (p.id.equals(CloudSolver.CUSTOM.id)) continue;
            total++;
            if (p.free) free++; else paid++;
        }
        if (tvProviderCount != null) {
            tvProviderCount.setText(free + " 免费" + (paid > 0 ? " · " + paid + " 付费" : ""));
        }

        int shown = 0;
        for (CloudSolver.Provider p : CloudSolver.ALL) {
            boolean cur = p.id.equals(curProvider.id);
            if (!providersExpanded && shown >= 3 && !cur) continue;
            boxProviders.addView(providerRow(p, cur));
            shown++;
        }

        if (tvExpand != null) {
            boolean canCollapse = providersExpanded || shown < CloudSolver.ALL.length;
            tvExpand.setVisibility(canCollapse ? View.VISIBLE : View.GONE);
            tvExpand.setText(providersExpanded
                    ? "收起"
                    : "查看全部 " + CloudSolver.ALL.length + " 个渠道");
        }
    }

    private View providerRow(final CloudSolver.Provider p, boolean cur) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.model_item_bg);
        row.setSelected(cur);
        row.setClickable(true);
        row.setFocusable(true);
        int ph = dp(10), pv = dp(9);
        row.setPadding(ph, pv, ph, pv);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rl.topMargin = dp(6);
        row.setLayoutParams(rl);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(p.label.replaceAll("（.*$", ""));
        name.setTextSize(13);
        name.setTextColor(clr(cur ? R.color.primary_deep : R.color.text_body));
        if (cur) name.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView sub = new TextView(this);
        sub.setText(p.id.equals(CloudSolver.CUSTOM.id)
                ? "OpenAI 兼容，自己填 Base URL 与模型名"
                : p.model);
        sub.setTextSize(11);
        sub.setTextColor(clr(R.color.text_hint));
        col.addView(name);
        col.addView(sub);
        row.addView(col, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tag = new TextView(this);
        tag.setText(p.id.equals(CloudSolver.CUSTOM.id) ? "自建" : (p.free ? "免费" : "付费"));
        tag.setTextSize(10);
        tag.setPadding(dp(7), dp(2), dp(7), dp(3));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(100));
        if (p.id.equals(CloudSolver.CUSTOM.id)) {
            g.setColor(clr(R.color.bg_field)); tag.setTextColor(clr(R.color.text_secondary));
        } else if (p.free) {
            g.setColor(clr(R.color.success_bg)); tag.setTextColor(clr(R.color.success));
        } else {
            g.setColor(clr(R.color.warning_bg)); tag.setTextColor(clr(R.color.warning));
        }
        tag.setBackground(g);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = dp(8);
        row.addView(tag, tl);

        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                if (p.id.equals(curProvider.id)) return;
                saveProviderFields(curProvider);
                curProvider = p;
                Prefs.setProviderId(MainActivity.this, curProvider.id);
                loadProviderFields(curProvider);
                updateCloudStateText();
                updateOnlineCard();
                renderProviders();
            }
        });
        return row;
    }

    private void loadProviderFields(CloudSolver.Provider p) {
        etKey.setText(Prefs.key(this, p));
        etBase.setText(Prefs.baseCustom(this));
        etModel.setText(Prefs.modelCustom(this));
        boxCustom.setVisibility(p.id.equals(CloudSolver.CUSTOM.id) ? View.VISIBLE : View.GONE);
        applyKeyMask();
        updateKeyNote();

        if (p.office != null && !p.office.isEmpty()) {
            tvOfficial.setVisibility(View.VISIBLE);
            String who = p.id.equals(CloudSolver.ZHIPU.id) ? "智谱"
                       : p.id.equals(CloudSolver.DEEPSEEK.id) ? "DeepSeek" : "服务商";
            tvOfficial.setText("去 " + who + " 官网申请 Key →");
        } else {
            tvOfficial.setVisibility(View.GONE);
        }
    }

    /** 空值不覆盖已有值——切换厂商的中间态不能把已存的 Key 冲掉。 */
    private void saveProviderFields(CloudSolver.Provider p) {
        if (p == null || etKey == null) return;
        String k = etKey.getText().toString().trim();
        if (!k.isEmpty() || Prefs.key(this, p).isEmpty()) Prefs.setKey(this, p, k);
        String base = etBase.getText().toString().trim();
        if (!base.isEmpty() || Prefs.baseCustom(this).isEmpty()) Prefs.setBaseCustom(this, base);
        String model = etModel.getText().toString().trim();
        if (!model.isEmpty() || Prefs.modelCustom(this).isEmpty()) Prefs.setModelCustom(this, model);
    }

    private void applyKeyMask() {
        etKey.setTransformationMethod(keyShown
                ? null : android.text.method.PasswordTransformationMethod.getInstance());
        try { etKey.setSelection(etKey.getText().length()); } catch (Throwable ignored) {}
        tvToggleKey.setText(keyShown ? "隐藏" : "显示");
    }

    /** 圆角徽章：文字色 + 底色一起换，避免深色字配浅蓝底的错配。 */
    /** 语义胶囊：浅底 + 同色系深字（颜色一律走 token，禁止写死 0xFF…） */
    private void setBadge(TextView v, String text, int fg, int bg) {
        setPill(v, text, bg, fg);
    }

    /** 取颜色 token（集中定义在 colors.xml） */
    private int clr(int resId) {
        return getColor(resId);
    }

    private void updateKeyNote() {
        if (tvKeyNote == null) return;
        String k = Prefs.key(this, Prefs.provider(this));
        if (k.isEmpty()) {
            tvKeyNote.setTextColor(clr(R.color.text_hint));
            tvKeyNote.setText("这个厂商还没填 Key（三家分开保存，互不影响）");
        } else {
            tvKeyNote.setTextColor(clr(R.color.success));
            tvKeyNote.setText("已保存 " + maskKey(k) + " · 共 " + k.length() + " 位");
        }
    }

    private static String maskKey(String k) {
        if (k.length() <= 8) return "••••••";
        return k.substring(0, 4) + "••••••" + k.substring(k.length() - 4);
    }

    private void openOfficial() {
        CloudSolver.Provider p = Prefs.provider(this);
        if (p.office == null || p.office.isEmpty()) {
            Toast.makeText(this, "自定义服务请自行填写 Base URL 和 Key", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(p.office)));
        } catch (Throwable t) {
            Toast.makeText(this, "打不开浏览器，请手动访问：\n" + p.office, Toast.LENGTH_LONG).show();
        }
    }

    private void testCloud() {
        if (testing || btnTest == null) return;
        saveProviderFields(curProvider);
        final CloudSolver.Provider p = Prefs.provider(this);
        if (!Prefs.cloudReady(this, p)) {
            setBadge(tvCloudState, "Key 未填完", R.color.danger, R.color.danger_bg);
            return;
        }
        testing = true;
        btnTest.setEnabled(false);
        setBadge(tvCloudState, "测试中…", R.color.text_secondary, R.color.bg_field);

        final String q = "以下哪个是电流的单位？";
        final List<String> opts = Arrays.asList("A. 伏特", "B. 安培", "C. 欧姆", "D. 瓦特");

        new Thread(new Runnable() {
            public void run() {
                final CloudSolver.Out o = CloudSolver.ask(p,
                        Prefs.key(MainActivity.this, p), Prefs.baseCustom(MainActivity.this),
                        Prefs.modelCustom(MainActivity.this), q, opts, false);
                ui.post(new Runnable() {
                    public void run() {
                        testing = false;
                        if (btnTest != null) btnTest.setEnabled(true);
                        if (tvCloudState == null) return;
                        if (o.error != null) {
                            setBadge(tvCloudState, "连接失败", R.color.danger, R.color.danger_bg);
                            Toast.makeText(MainActivity.this, "✗ " + o.error, Toast.LENGTH_LONG).show();
                        } else if ("B".equals(o.letters)) {
                            setBadge(tvCloudState, "连接正常", R.color.success, R.color.success_bg);
                            Toast.makeText(MainActivity.this,
                                    "✓ 答对了 · " + o.ms + " ms", Toast.LENGTH_SHORT).show();
                            if (!Prefs.cloudOn(MainActivity.this)) {
                                Prefs.setCloudOn(MainActivity.this, true);
                                if (swCloudOn != null) swCloudOn.setChecked(true);
                            }
                        } else {
                            setBadge(tvCloudState, "已连通", R.color.warning, R.color.warning_bg);
                            Toast.makeText(MainActivity.this,
                                    "连上了，但样例题答成 " + o.letters + " · " + o.ms + " ms",
                                    Toast.LENGTH_LONG).show();
                        }
                        updateOnlineCard();
                    }
                });
            }
        }).start();
    }

    private void updateCloudStateText() {
        if (tvCloudState == null) return;
        CloudSolver.Provider p = Prefs.provider(this);
        boolean on = Prefs.cloudOn(this);
        boolean ready = Prefs.cloudReady(this, p);
        if (!ready) {
            setBadge(tvCloudState, "未配置", R.color.text_hint, R.color.bg_field);
        } else if (on) {
            if (CloudSolver.cooling()) {
                setBadge(tvCloudState, "已暂停 " + CloudSolver.cooldownLeftSec() + "s", R.color.warning, R.color.warning_bg);
            } else {
                setBadge(tvCloudState, "已启用", R.color.success, R.color.success_bg);
            }
        } else {
            setBadge(tvCloudState, "已配置 · 未启用", R.color.text_secondary, R.color.bg_field);
        }
        updateKeyNote();
    }

    // ================= 本地配置弹窗 =================

    private void showLocalDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_local, null);
        swLocalOn    = v.findViewById(R.id.swLocalOn);
        boxModels    = v.findViewById(R.id.boxModels);
        pb           = v.findViewById(R.id.pb);
        tvModelState = v.findViewById(R.id.tvModelState);
        btnReload    = v.findViewById(R.id.btnReload);

        binding = true;
        swLocalOn.setChecked(Prefs.localOn(this));
        binding = false;

        swLocalOn.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton c, boolean on) {
                Prefs.setLocalOn(MainActivity.this, on);
                if (on && !AnswerEngine.get().ready()
                        && ModelManager.canLoad(MainActivity.this, Prefs.spec(MainActivity.this))) {
                    loadModel();
                }
                updateLocalCard();
            }
        });
        btnReload.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                if (loadingModel) return;
                if (AnswerEngine.get().ready()) {
                    AnswerEngine.get().unloadAsync(new Runnable() {
                        public void run() { loadModel(); }
                    });
                } else {
                    loadModel();
                }
            }
        });

        refresh();

        AlertDialog d = new AlertDialog.Builder(this, R.style.WhiteDialog)
                .setView(v)
                .create();
        final View dlgV = v;
        dlgV.findViewById(R.id.btnSaveLocal).setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { d.dismiss(); }
        });
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface dd) {
                swLocalOn = null; boxModels = null;
                pb = null; tvModelState = null; btnReload = null;
                QService.resumeOverlayAfterDialog();
                refresh();
            }
        });
        QService.pauseOverlayForDialog();
        showSheet(d);
        if (d.getWindow() != null) {
            d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void renderModels() {
        if (boxModels == null) return;
        boxModels.removeAllViews();

        for (final ModelManager.Spec s : ModelManager.ALL) {
            final boolean has    = ModelManager.isReady(this, s);
            final boolean usable = ModelManager.looksUsable(this, s);
            final boolean cur    = usable && s.id.equals(Prefs.specId(this));
            long len = ModelManager.fileOf(this, s).length();

            View item = getLayoutInflater().inflate(R.layout.item_model, boxModels, false);
            TextView tvBadge = item.findViewById(R.id.tvBadge);
            TextView tvName  = item.findViewById(R.id.tvName);
            TextView tvSize  = item.findViewById(R.id.tvSize);
            TextView tvBrief = item.findViewById(R.id.tvBrief);
            TextView tvState = item.findViewById(R.id.tvState);
            TextView tvDel   = item.findViewById(R.id.tvDel);

            android.graphics.drawable.GradientDrawable badgeBg = new android.graphics.drawable.GradientDrawable();
            badgeBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            badgeBg.setColor(cur ? clr(R.color.primary) : clr(R.color.stroke));
            tvBadge.setBackground(badgeBg);
            tvBadge.setTextColor(cur ? 0xFFFFFFFF : clr(R.color.text_hint));
            tvBadge.setText(s.badge);

            tvName.setText(s.name);
            tvSize.setText(has ? mb(len) : (usable ? "体积异常 " + mb(len) : mb(s.bytes)));
            tvBrief.setText(s.brief);
            tvName.setTextColor(clr(cur ? R.color.primary : R.color.text_primary));

            android.graphics.drawable.GradientDrawable actBg = new android.graphics.drawable.GradientDrawable();
            actBg.setCornerRadius(dp(100));
            if (cur) {
                actBg.setColor(clr(R.color.success));
                tvState.setText(loadingModel ? "载入中…" : "✓ 使用中");
                tvState.setTextColor(0xFFFFFFFF);
                tvState.setEnabled(false);
            } else if (usable) {
                actBg.setColor(clr(R.color.primary));
                tvState.setText("使用");
                tvState.setTextColor(0xFFFFFFFF);
                tvState.setEnabled(!downloading);
            } else {
                actBg.setColor(0x00000000);
                actBg.setStroke(dp(1), clr(R.color.primary));
                tvState.setText("下载");
                tvState.setTextColor(clr(R.color.primary));
                tvState.setEnabled(!downloading);
            }
            tvState.setBackground(actBg);

            View.OnClickListener act = new View.OnClickListener() {
                public void onClick(View x) {
                    if (cur || downloading) return;
                    if (usable) {
                        Prefs.setSpecId(MainActivity.this, s.id);
                        loadModel();
                    } else {
                        confirmDownload(s);
                    }
                }
            };
            item.setOnClickListener(act);
            tvState.setOnClickListener(act);

            if (usable) {
                tvDel.setVisibility(View.VISIBLE);
                tvDel.setEnabled(!downloading && !loadingModel);
                android.graphics.drawable.GradientDrawable delBg = new android.graphics.drawable.GradientDrawable();
                delBg.setCornerRadius(dp(100));
                delBg.setColor(0x00000000);
                delBg.setStroke(dp(1), clr(R.color.danger));
                tvDel.setBackground(delBg);
                tvDel.setTextColor(clr(R.color.danger));
                tvDel.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View x) {
                        x.getParent().requestDisallowInterceptTouchEvent(true);
                        confirmDelete(s);
                    }
                });
            }
            boxModels.addView(item);
        }
    }

    private void updateModelStateText() {
        if (tvModelState == null) return;
        ModelManager.Spec s = Prefs.spec(this);
        boolean has   = ModelManager.isReady(this, s);
        boolean ready = AnswerEngine.get().ready();
        long len = ModelManager.fileOf(this, s).length();

        if (!Prefs.localOn(this)) {
            tvModelState.setTextColor(clr(R.color.text_hint));
            tvModelState.setText("本地答题已关闭，模型不参与解题");
        } else if (ready) {
            tvModelState.setTextColor(clr(R.color.success));
            tvModelState.setText("已载入「" + s.name + "」" + mb(len));
        } else if (loadingModel) {
            tvModelState.setTextColor(clr(R.color.text_hint));
            tvModelState.setText("载入中…（" + mb(len) + "）");
        } else if (has) {
            tvModelState.setTextColor(clr(R.color.warning));
            tvModelState.setText("已下载 " + mb(len) + "，点「使用」加载");
        } else {
            long part = 0;
            java.io.File pf = new java.io.File(ModelManager.fileOf(this, s).getAbsolutePath() + ".part");
            if (pf.exists()) part = pf.length();
            tvModelState.setTextColor(clr(R.color.danger));
            tvModelState.setText(part > 0 ? "下载中断，已存 " + mb(part) + "，可续传" : "未下载");
        }
    }

    private void confirmDownload(final ModelManager.Spec s) {
        new AlertDialog.Builder(this)
                .setTitle("下载「" + s.name + "」？")
                .setMessage("模型 " + mb(s.bytes) + "，建议在 Wi-Fi 下下载。\n"
                        + "下载一次后永久离线可用，答题不再消耗流量。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始下载", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Prefs.setSpecId(MainActivity.this, s.id);
                        startDownload();
                    }
                })
                .show();
    }

    private void confirmDelete(final ModelManager.Spec s) {
        new AlertDialog.Builder(this)
                .setTitle("删除「" + s.name + "」？")
                .setMessage("会删掉 " + mb(s.bytes) + " 的模型文件，下次用要重新下载。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        ModelManager.delete(MainActivity.this, s);
                        AnswerEngine.get().unloadAsync(new Runnable() {
                            public void run() {
                                syncSpecWithDisk();
                                refresh();
                                updateLocalCard();
                            }
                        });
                    }
                })
                .show();
    }

    private void syncSpecWithDisk() {
        if (ModelManager.canLoad(this, Prefs.spec(this))) return;
        ModelManager.Spec other = ModelManager.pickReady(this, null);
        if (other != null) Prefs.setSpecId(this, other.id);
    }

    private void startDownload() {
        if (downloading) return;
        final ModelManager.Spec s = Prefs.spec(this);
        downloading = true;
        if (pb != null) { pb.setVisibility(View.VISIBLE); pb.setProgress(0); }
        if (tvModelState != null) {
            tvModelState.setTextColor(clr(R.color.text_hint));
            tvModelState.setText("开始下载…");
        }
        renderModels();

        new Thread(new Runnable() {
            public void run() {
                try {
                    ModelManager.download(MainActivity.this, s, new ModelManager.Progress() {
                        public void onProgress(final long done, final long total) {
                            ui.post(new Runnable() {
                                public void run() {
                                    int pct = total > 0 ? (int) (done * 100 / total) : 0;
                                    if (pb != null) pb.setProgress(pct);
                                    if (tvModelState != null) {
                                        tvModelState.setText("下载中 " + pct + "% · " + mb(done) + " / " + mb(total));
                                    }
                                }
                            });
                        }
                    });
                    ui.post(new Runnable() {
                        public void run() {
                            downloading = false;
                            if (pb != null) pb.setVisibility(View.GONE);
                            Toast.makeText(MainActivity.this, "下载完成", Toast.LENGTH_SHORT).show();
                            refresh();
                            loadModel();
                        }
                    });
                } catch (final Throwable t) {
                    Log.e(TAG, "下载失败", t);
                    ui.post(new Runnable() {
                        public void run() {
                            downloading = false;
                            if (pb != null) pb.setVisibility(View.GONE);
                            if (tvModelState != null) {
                                tvModelState.setText("下载失败：" + t.getMessage());
                                tvModelState.setTextColor(clr(R.color.danger));
                            }
                            renderModels();
                        }
                    });
                }
            }
        }).start();
    }

    private void loadModel() {
        if (loadingModel) return;
        final ModelManager.Spec s = Prefs.spec(this);
        if (!ModelManager.canLoad(this, s)) {
            Toast.makeText(this, "先下载模型", Toast.LENGTH_SHORT).show();
            return;
        }
        loadingModel = true;
        refresh();
        if (tvModelState != null) {
            tvModelState.setTextColor(clr(R.color.text_hint));
            tvModelState.setText("载入中…（1.5B 首次约 10~30 秒）");
        }
        AnswerEngine.get().loadAsync(this, s, new Runnable() {
            public void run() {
                loadingModel = false;
                refresh();
                updateLocalCard();
                Toast.makeText(MainActivity.this,
                        AnswerEngine.get().ready() ? "模型就绪" : "载入失败",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ================= 诊断 =================

    private void showDiagnosis() {
        QService.Diag d = QService.diagnose();
        StringBuilder sb = new StringBuilder();
        boolean soOk = Llama.ensureLoaded();
        sb.append("结论：").append(d.reason).append("\n\n");
        sb.append("原生库：").append(soOk ? "OK · arm64" : "加载失败 " + Llama.loadError()).append("\n");
        if (!d.pkg.isEmpty()) sb.append("当前界面：").append(d.pkg).append("\n");
        sb.append("读到文字：").append(d.texts.size()).append(" 段\n");
        sb.append("认出选项：").append(d.options).append(" 个\n");
        sb.append("\n==== 识别结果（这才是发给模型的）====\n");
        sb.append("题干：").append(d.question.isEmpty() ? "（空）" : d.question).append("\n");
        if (!d.optionLines.isEmpty()) {
            sb.append("选项：\n");
            for (String o : d.optionLines) sb.append("   ").append(o).append("\n");
        } else {
            sb.append("选项：（一个都没认出来）\n");
        }
        if (!d.texts.isEmpty()) {
            sb.append("\n---- 原始文字 ----\n");
            int n = Math.min(d.texts.size(), 40);
            for (int i = 0; i < n; i++) {
                String t = d.texts.get(i);
                if (t.length() > 60) t = t.substring(0, 60) + "…";
                sb.append(i + 1).append(". ").append(t).append('\n');
            }
            if (d.texts.size() > n) sb.append("… 还有 ").append(d.texts.size() - n).append(" 段\n");
        }
        sb.append("\n\n提示：把这一屏截图发我可以定位问题。");

        final String full = sb.toString();
        new AlertDialog.Builder(this)
                .setTitle("读屏诊断")
                .setMessage(full)
                .setPositiveButton("知道了", null)
                .setNeutralButton("复制", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dd, int w) {
                        try {
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("读屏诊断", full));
                                Toast.makeText(MainActivity.this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
                            }
                        } catch (Throwable t) {
                            Toast.makeText(MainActivity.this, "复制失败，请截图", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    // ================= 二级弹窗 =================

    /** 统一弹窗宽度：左右各留约 27dp，别贴着屏幕边。 */
    private void showSheet(AlertDialog d) {
        d.show();
        if (d.getWindow() != null) {
            int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.86f);
            d.getWindow().setLayout(w, android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    /** 内容较长的弹窗（设置）：高度锁到屏高 84%，内部自己滚动，不会顶出屏幕。 */
    private void showTallSheet(AlertDialog d) {
        d.show();
        if (d.getWindow() != null) {
            int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.86f);
            int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.84f);
            d.getWindow().setLayout(w, h);
        }
    }

    private void confirm(String title, String msg, final Runnable ok) {
        new AlertDialog.Builder(this, R.style.WhiteDialog)
                .setTitle(title)
                .setMessage(msg)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { ok.run(); }
                }).show();
    }

    /** 复制本场答案：主界面「复制」和记录弹窗共用。 */
    private void copyAnswers() {
        String txt = Record.exportText();
        if (txt.isEmpty()) {
            Toast.makeText(this, "本场还没有记录", Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(
                android.content.ClipData.newPlainText("answers", txt));
        Toast.makeText(this, "已复制 " + Record.sessionSnapshot().size() + " 题答案",
                Toast.LENGTH_SHORT).show();
    }

    /** 设置：解题行为（自动点击/振动/复核/阈值）+ 关于（使用说明/版本号）。 */
    private void showSettingsDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        swClick   = v.findViewById(R.id.swClick);
        swVibrate = v.findViewById(R.id.swVibrate);
        swDual    = v.findViewById(R.id.swDual);
        sbTh      = v.findViewById(R.id.sbTh);
        tvTh      = v.findViewById(R.id.tvTh);
        tvSettingsVersion = v.findViewById(R.id.tvSettingsVersion);

        binding = true;
        swClick.setChecked(Prefs.autoClick(this));
        swVibrate.setChecked(Prefs.vibrate(this));
        swDual.setChecked(Prefs.dualCheck(this));
        sbTh.setProgress(Prefs.thresholdPct(this));
        binding = false;
        updateThLabel(Prefs.thresholdPct(this));
        if (tvSettingsVersion != null) {
            try {
                String vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                tvSettingsVersion.setText("AI 答题 v" + vn + " · 本地推理不联网");
            } catch (Throwable t) {
                tvSettingsVersion.setText("AI 答题");
            }
        }

        swClick.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton c, boolean on) {
                if (binding) return;
                Prefs.setAutoClick(MainActivity.this, on);
            }
        });
        swVibrate.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton c, boolean on) {
                if (binding) return;
                Prefs.setVibrate(MainActivity.this, on);
            }
        });
        swDual.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton c, boolean on) {
                if (binding) return;
                Prefs.setDualCheck(MainActivity.this, on);
            }
        });
        sbTh.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                Prefs.setThresholdPct(MainActivity.this, p);
                updateThLabel(p);
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });

        final AlertDialog d = new AlertDialog.Builder(this, R.style.WhiteDialog).setView(v).create();
        v.findViewById(R.id.btnSaveSettings).setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { d.dismiss(); }
        });
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface x) {
                swClick = null; swVibrate = null; swDual = null;
                sbTh = null; tvTh = null; tvSettingsVersion = null;
                QService.resumeOverlayAfterDialog();
            }
        });
        QService.pauseOverlayForDialog();   // 弹窗期间先收走悬浮球，别压着开关
        showTallSheet(d);
    }

    /** 本场记录：完整统计 + 四类清理。 */
    private void showRecordDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_record, null);
        tvSession = v.findViewById(R.id.tvSession);
        refresh();                       // 打开前先把统计填进去

        final AlertDialog d = new AlertDialog.Builder(this, R.style.WhiteDialog).setView(v).create();
        v.findViewById(R.id.btnCopy).setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { copyAnswers(); }
        });
        v.findViewById(R.id.btnClearSession).setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                confirm("清空本场记录？", "只清空「本场」列表和统计，题库记忆会保留。", new Runnable() {
                    public void run() { Record.clearSession(MainActivity.this); refresh(); }
                });
            }
        });
        v.findViewById(R.id.btnClearBank).setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                int n = Record.bankSize(MainActivity.this);
                confirm("清空题库记忆？",
                        "当前记住了 " + n + " 道题。清空后这些题下次会重新推理（变慢，但答案可能更新）。",
                        new Runnable() {
                            public void run() {
                                Record.clearBank(MainActivity.this);
                                refresh();
                                Toast.makeText(MainActivity.this, "题库已清空", Toast.LENGTH_SHORT).show();
                            }
                        });
            }
        });
        v.findViewById(R.id.btnResetCalls).setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                Prefs.resetCloudCalls(MainActivity.this);
                refresh();
                Toast.makeText(MainActivity.this, "在线计数已清零", Toast.LENGTH_SHORT).show();
            }
        });
        v.findViewById(R.id.btnCloseRecord).setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { d.dismiss(); }
        });
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface x) {
                tvSession = null;
                QService.resumeOverlayAfterDialog();
            }
        });
        QService.pauseOverlayForDialog();
        showSheet(d);
    }

    /** 自测：手动试一道 + 性能自测。 */
    private void showSelfTestDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_selftest, null);
        etQ      = v.findViewById(R.id.etQ);
        tvOut    = v.findViewById(R.id.tvOut);
        tvBench  = v.findViewById(R.id.tvBench);
        btnBench = v.findViewById(R.id.btnBench);

        final AlertDialog d = new AlertDialog.Builder(this, R.style.WhiteDialog).setView(v).create();
        v.findViewById(R.id.btnTry).setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { tryManual(); }
        });
        btnBench.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { runBenchmark(); }
        });
        v.findViewById(R.id.btnCloseSelftest).setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { d.dismiss(); }
        });
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface x) {
                etQ = null; tvOut = null; tvBench = null; btnBench = null;
                QService.resumeOverlayAfterDialog();
            }
        });
        QService.pauseOverlayForDialog();
        showSheet(d);
    }

    /** 使用说明。 */
    // ================= 通用 =================

    private void updateThLabel(int p) {
        if (tvTh == null) return;
        tvTh.setText("置信度阈值 " + p + "%");
    }

    private String mb(long bytes) {
        if (bytes <= 0) return "0 MB";
        return String.format(java.util.Locale.US, "%.0f MB", bytes / 1048576.0);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---- 性能自测 ----

    private static final String[][] BENCH = {
            {"以下哪个是电流的单位？", "A. 伏特", "B. 安培", "C. 欧姆", "D. 瓦特"},
            {"我国安全生产工作的方针是？", "A. 安全第一、预防为主、综合治理", "B. 生产第一、安全第二", "C. 效益优先、兼顾安全", "D. 谁主管谁负责"},
            {"触电急救的第一步是？", "A. 立即做人工呼吸", "B. 迅速脱离电源", "C. 送医院", "D. 拨打120等待"},
            {"三相异步电动机的转向由什么决定？", "A. 电源频率", "B. 电源相序", "C. 负载大小", "D. 电压高低"},
            {"电气设备着火时，应首先？", "A. 用水扑救", "B. 切断电源", "C. 用泡沫灭火器", "D. 打开门窗通风"},
    };

    private boolean benchLocalOnly = true;

    private void runBenchmark() {
        boolean cloudOn = Prefs.cloudOn(this) && Prefs.cloudReady(this, Prefs.provider(this));
        boolean localOn = Prefs.localOn(this) && AnswerEngine.get().ready();
        if (!cloudOn && !localOn) {
            Toast.makeText(this, "先启用在线或本地模型", Toast.LENGTH_SHORT).show();
            return;
        }
        // 自测的目的是量本地速度：本地能用就强制只走本地，绝不花在线额度
        if (localOn) {
            benchLocalOnly = true;
            benchStep(0, new ArrayList<Long>(), new ArrayList<String>());
            return;
        }
        // 只有在线可用，才需要用户点头
        new AlertDialog.Builder(this)
                .setTitle("会用在线额度")
                .setMessage("本地模型没加载，这次自测需要调用 5 次在线接口"
                        + (Prefs.provider(this).free ? "（免费渠道，不产生费用）" : "（付费渠道，会产生少量费用）")
                        + "。继续吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("继续", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        benchLocalOnly = false;
                        benchStep(0, new ArrayList<Long>(), new ArrayList<String>());
                    }
                }).show();
    }

    private void benchStep(final int i, final List<Long> times, final List<String> sources) {
        // 弹窗可能在自测跑完前被关掉，这里统一兜底
        if (tvBench == null) return;
        if (i >= BENCH.length) {
            if (times.isEmpty()) { tvBench.setText("测不出结果"); return; }
            long sum = 0, min = Long.MAX_VALUE, max = 0;
            for (long t : times) { sum += t; if (t < min) min = t; if (t > max) max = t; }
            StringBuilder sb = new StringBuilder();
            sb.append("5 题平均 ").append(sum / times.size()).append(" ms")
              .append("（最快 ").append(min).append(" / 最慢 ").append(max).append("）\n");
            sb.append("逐题：");
            for (int k = 0; k < times.size(); k++) {
                if (k > 0) sb.append(" / ");
                sb.append(times.get(k));
            }
            sb.append("\n来源：").append(sources.isEmpty() ? "-" : sources.get(0));
            sb.append("\nCPU：").append(cpuSummary());
            tvBench.setText(sb.toString());
            return;
        }
        tvBench.setText("测试中 " + (i + 1) + "/" + BENCH.length + "…");
        String[] c = BENCH[i];
        List<String> lines = new ArrayList<>();
        for (int k = 1; k < c.length; k++) lines.add(c[k]);
        String[] letters = new String[c.length - 1];
        for (int k = 0; k < letters.length; k++) letters[k] = String.valueOf((char) ('A' + k));

        AnswerEngine.get().answerAsync(this, c[0], lines, letters, false, benchLocalOnly,
                new AnswerEngine.Cb() {
            public void onResult(AnswerEngine.Result r) {
                if (r.error != null && r.letters.isEmpty()) {
                    if (tvBench != null) tvBench.setText("第 " + (i + 1) + " 题失败：" + r.error);
                    return;
                }
                times.add(r.ms);
                if (sources.isEmpty()) sources.add(r.source);
                benchStep(i + 1, times, sources);
            }
        });
    }

    private String cpuSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(Runtime.getRuntime().availableProcessors()).append(" 核");
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader("/proc/cpuinfo"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("Features")) {
                    sb.append(" · dotprod ").append(line.contains("asimddp") ? "✓" : "✗");
                    sb.append(" · i8mm ").append(line.contains("i8mm") ? "✓" : "✗");
                    sb.append(" · sve2 ").append(line.contains("sve2") ? "✓" : "✗");
                    break;
                }
            }
            br.close();
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    // ---- 手动测试 ----

    private void tryManual() {
        if (etQ == null || tvOut == null) return;
        final TextView out = tvOut;
        boolean cloudOn = Prefs.cloudOn(this) && Prefs.cloudReady(this, Prefs.provider(this));
        boolean localReady = Prefs.localOn(this) && AnswerEngine.get().ready();
        if (!cloudOn && !localReady) {
            Toast.makeText(this, "先启用在线或本地模型", Toast.LENGTH_SHORT).show();
            return;
        }
        String raw = etQ.getText().toString().trim();
        if (raw.isEmpty()) {
            Toast.makeText(this, "先粘一道题", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> lines = new ArrayList<>();
        for (String l : raw.split("\n")) {
            String t = l.trim();
            if (!t.isEmpty()) lines.add(t);
        }
        Layout.Parsed p = Layout.parse(lines);
        if (p.options.size() < 2) {
            out.setText("没认出选项，请确认每行以「A. 」开头");
            return;
        }
        List<String> letters = new ArrayList<>();
        List<String> optLines = new ArrayList<>();
        for (Layout.Opt o : p.options) {
            letters.add(o.letter);
            optLines.add(o.letter + ". " + o.text);
        }
        boolean multi = raw.contains("多选") || raw.contains("不定项");

        out.setText("推理中…");
        AnswerEngine.get().answerAsync(this, p.question, optLines,
                letters.toArray(new String[0]), multi, localReady, new AnswerEngine.Cb() {
                    public void onResult(AnswerEngine.Result r) {
                        if (tvOut == null) return;
                        if (r.error != null && r.letters.isEmpty()) {
                            tvOut.setText("失败：" + r.error);
                            return;
                        }
                        tvOut.setText("答案 " + r.letters + "　" + r.source + "　" + r.ms + " ms"
                                + (r.error != null ? "\n备注：" + r.error : ""));
                    }
                });
    }
}
