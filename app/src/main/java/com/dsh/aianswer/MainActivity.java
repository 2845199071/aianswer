package com.dsh.aianswer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
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
    // v7.1 新增开关
    private Switch swHuman, swAutoNext, swVote, swRoute, swExplain;
    private TextView tvAutoNextHint;
    private Switch swSelfFirst;
    private TextView tvVoteHint, tvRouteHint, tvThHint;

    // ---- 题库中心 ----
    private LinearLayout boxBank;
    private LinearLayout boxConflictList;
    private LinearLayout boxLetters;
    private TextView tvBankEmpty, tvBankCount, tvBankHint;
    private TextView segAll, segConflict, segWrong;
    private int bankFilter = 0;                 // 0 全部 / 1 有分歧 / 2 错题
    // ---- 题库批量删除 ----
    private boolean bankSelectMode = false;
    private final java.util.Set<String> selectedKeys = new java.util.LinkedHashSet<>();  // 用 key 标识，跨筛选累计；必须去重
    private final java.util.Map<String, View> bankRowViews = new java.util.HashMap<>();
    private TextView btnBankSelect, btnBankAll, btnBankDelete;
    private View boxBankAction;
    // ---- 自选题库 ----
    private static final int REQ_IMPORT = 1001;
    private static final int REQ_EXPORT_MISS = 1002;
    private LinearLayout boxSelf;
    private TextView tvSelfEmpty, tvSelfCount;
    // 批量删除撤销
    private TextView tvUndoBar;
    private final List<Record.Bank> lastDeleted = new ArrayList<>();
    private final Runnable clearUndo = new Runnable() {
        public void run() {
            lastDeleted.clear();
            if (tvUndoBar != null) tvUndoBar.setVisibility(View.GONE);
        }
    };
    private TextView tvSelfEntryCount;
    private final List<String> draftLetters = new ArrayList<>();   // 详情页正在勾的答案
    private boolean bankMulti = false;         // 该条目是否多选（原答案多于一个字母）
    private Record.Bank curBank;
    private View conflictWrap;                 // 详情页冲突区容器，采纳后整块收起
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
    // ---- 模型市场 ----
    private LinearLayout boxMarket;
    private TextView tvMarketEmpty;
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
        findViewById(R.id.btnOpenBank).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showBankDialog(); }
        });
        // 长按直达自选题库：上传题库是高频动作，不该埋在第二层
        findViewById(R.id.btnOpenBank).setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                Toast.makeText(MainActivity.this, "自选题库", Toast.LENGTH_SHORT).show();
                showSelfBankDialog();
                return true;
            }
        });
        findViewById(R.id.btnOpenSelftest).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showSelfTestDialog(); }
        });
        findViewById(R.id.btnCopyQuick).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { copyAnswers(); }
        });

        refresh();

        // 读屏没开时所有功能都是死的。首次进来自动说清楚一次，之后不再打扰
        if (!accEnabled() && !Prefs.accGuided(this)) {
            Prefs.setAccGuided(this, true);
            new AlertDialog.Builder(this, R.style.WhiteDialog)
                    .setTitle("还差一步：开启读屏")
                    .setMessage("这个 App 靠系统「无障碍」读屏幕上的题目和选项。\n"
                            + "不开的话：悬浮球不出现、不会自动出答案，只能手动操作。\n\n"
                            + "去系统设置里找到「AI 答题」并打开即可。")
                    .setNegativeButton("稍后", null)
                    .setPositiveButton("去开启", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            try {
                                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                                Toast.makeText(MainActivity.this, "找到「AI 答题」并打开",
                                        Toast.LENGTH_LONG).show();
                            } catch (Throwable t) {
                                Toast.makeText(MainActivity.this, "打不开无障碍设置",
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    })
                    .show();
        }

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
                Record.BankCounts bc = Record.bankCounts(MainActivity.this);
                sb2.append("\n题库已记住 ").append(bc.total).append(" 道题");
                if (bc.wrong > 0) sb2.append(" · 错题 ").append(bc.wrong);
                if (bc.conflict > 0) sb2.append(" · 分歧 ").append(bc.conflict);
                if (bc.locked > 0) sb2.append(" · 锁定 ").append(bc.locked);
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

        v.findViewById(R.id.tvMarketEntry).setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { showModelMarket(); }
        });

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

    // ================= 模型市场（v7.2） =================

    /** 从「本地模型」弹窗进入的二级弹窗：清单来自 assets/models.json。 */
    private void showModelMarket() {
        View v = getLayoutInflater().inflate(R.layout.dialog_model_market, null);
        boxMarket     = v.findViewById(R.id.boxMarket);
        tvMarketEmpty = v.findViewById(R.id.tvMarketEmpty);

        final AlertDialog d = new AlertDialog.Builder(this, R.style.WhiteDialog).setView(v).create();
        v.findViewById(R.id.btnCloseMarket).setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { d.dismiss(); }
        });
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface x) {
                boxMarket = null; tvMarketEmpty = null;
                refresh();
                renderModels();
                QService.resumeOverlayAfterDialog();
            }
        });
        QService.pauseOverlayForDialog();
        showTallSheet(d);
        renderMarket();
    }

    private void renderMarket() {
        if (boxMarket == null) return;
        boxMarket.removeAllViews();
        List<ModelMarket.Item> list = ModelMarket.all(this);
        if (tvMarketEmpty != null) {
            tvMarketEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        }
        for (ModelMarket.Item it : list) boxMarket.addView(marketRow(it));
    }

    private View marketRow(final ModelMarket.Item it) {
        final ModelManager.Spec spec = ModelMarket.specOf(it);
        final boolean has    = spec != null && ModelManager.isReady(this, spec);
        final boolean usable = spec != null && ModelManager.looksUsable(this, spec);
        final boolean cur    = it.id.equals(Prefs.specId(this));

        View item = getLayoutInflater().inflate(R.layout.item_market, boxMarket, false);
        TextView tvBadge = item.findViewById(R.id.tvMBadge);
        TextView tvName  = item.findViewById(R.id.tvMName);
        TextView tvTag   = item.findViewById(R.id.tvMStateTag);
        TextView tvLines = item.findViewById(R.id.tvMLines);
        TextView tvAct   = item.findViewById(R.id.tvMAct);
        TextView tvDel   = item.findViewById(R.id.tvMDel);

        android.graphics.drawable.GradientDrawable badgeBg = new android.graphics.drawable.GradientDrawable();
        badgeBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        badgeBg.setColor(cur ? clr(R.color.primary) : clr(R.color.stroke));
        tvBadge.setBackground(badgeBg);
        tvBadge.setTextColor(cur ? 0xFFFFFFFF : clr(R.color.text_hint));
        tvBadge.setText(it.badge);

        tvName.setText(it.name);
        tvName.setTextColor(clr(cur ? R.color.primary : R.color.text_primary));
        tvLines.setText(it.lines());

        if (cur) {
            tvTag.setVisibility(View.VISIBLE);
            tvTag.setText("当前");
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setCornerRadius(dp(100));
            g.setColor(clr(R.color.primary_soft));
            tvTag.setBackground(g);
        } else {
            tvTag.setVisibility(View.GONE);
        }

        android.graphics.drawable.GradientDrawable actBg = new android.graphics.drawable.GradientDrawable();
        actBg.setCornerRadius(dp(100));
        if (it.planned()) {
            actBg.setColor(clr(R.color.bg_field));
            tvAct.setText("敬请期待");
            tvAct.setTextColor(clr(R.color.text_hint));
            tvAct.setEnabled(false);
        } else if (cur && usable) {
            actBg.setColor(clr(R.color.success));
            tvAct.setText(loadingModel ? "载入中…" : "✓ 使用中");
            tvAct.setTextColor(0xFFFFFFFF);
            tvAct.setEnabled(false);
        } else if (usable) {
            actBg.setColor(clr(R.color.primary));
            tvAct.setText("使用");
            tvAct.setTextColor(0xFFFFFFFF);
            tvAct.setEnabled(!downloading);
        } else if (has) {
            actBg.setColor(clr(R.color.primary));
            tvAct.setText("使用");
            tvAct.setTextColor(0xFFFFFFFF);
            tvAct.setEnabled(!downloading);
        } else {
            actBg.setColor(0x00000000);
            actBg.setStroke(dp(1), clr(R.color.primary));
            tvAct.setText(labelWithPart(it));
            tvAct.setTextColor(clr(R.color.primary));
            tvAct.setEnabled(!downloading);
        }
        tvAct.setBackground(actBg);

        View.OnClickListener act = new View.OnClickListener() {
            public void onClick(View x) {
                if (it.planned() || downloading || loadingModel) return;
                if (usable || has) {
                    Prefs.setSpecId(MainActivity.this, it.id);
                    if (AnswerEngine.get().ready()) {
                        Toast.makeText(MainActivity.this, "切换中，约 3~5 秒", Toast.LENGTH_SHORT).show();
                        AnswerEngine.get().unloadAsync(new Runnable() {
                            public void run() { loadModel(); }
                        });
                    } else {
                        loadModel();
                    }
                    ui.postDelayed(new Runnable() {
                        public void run() { renderMarket(); refresh(); }
                    }, 600);
                } else if (spec != null) {
                    confirmDownload(spec);
                    pollMarket();
                }
            }
        };
        item.setOnClickListener(act);
        tvAct.setOnClickListener(act);

        if (usable && !cur) {
            tvDel.setVisibility(View.VISIBLE);
            tvDel.setEnabled(!downloading && !loadingModel);
            tvDel.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    if (spec == null) return;
                    confirmDelete(spec);
                    pollMarket();
                }
            });
        }
        return item;
    }

    /** 已下了一部分就显示「续传」，否则「下载」。 */
    private String labelWithPart(ModelMarket.Item it) {
        if (it.planned()) return "敬请期待";
        java.io.File pf = new java.io.File(ModelManager.fileOf(this, ModelMarket.specOf(it)).getAbsolutePath() + ".part");
        return pf.exists() && pf.length() > 0 ? "续传" : "下载";
    }

    /** 下载/删除是异步的，隔一会儿刷一次列表，别让按钮一直停在旧状态。 */
    private void pollMarket() {
        ui.postDelayed(new Runnable() {
            public void run() {
                if (boxMarket == null) return;
                renderMarket();
                if (downloading) ui.postDelayed(this, 800);
            }
        }, 800);
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

    /** 设置：解题行为（自动点击/拟人/振动/复核/投票/路由/解析/阈值）+ 关于。 */
    private void showSettingsDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        swClick   = v.findViewById(R.id.swClick);
        swVibrate = v.findViewById(R.id.swVibrate);
        swDual    = v.findViewById(R.id.swDual);
        swHuman   = v.findViewById(R.id.swHuman);
        swAutoNext = v.findViewById(R.id.swAutoNext);
        tvAutoNextHint = v.findViewById(R.id.tvAutoNextHint);
        swVote    = v.findViewById(R.id.swVote);
        swRoute   = v.findViewById(R.id.swRoute);
        swExplain = v.findViewById(R.id.swExplain);
        swSelfFirst = v.findViewById(R.id.swSelfFirst);
        tvVoteHint= v.findViewById(R.id.tvVoteHint);
        tvRouteHint = v.findViewById(R.id.tvRouteHint);
        sbTh      = v.findViewById(R.id.sbTh);
        tvTh      = v.findViewById(R.id.tvTh);
        tvThHint  = v.findViewById(R.id.tvThHint);
        tvSettingsVersion = v.findViewById(R.id.tvSettingsVersion);

        binding = true;
        swClick.setChecked(Prefs.autoClick(this));
        swVibrate.setChecked(Prefs.vibrate(this));
        swDual.setChecked(Prefs.dualCheck(this));
        swHuman.setChecked(Prefs.humanClick(this));
        swAutoNext.setChecked(Prefs.autoNext(this));
        swVote.setChecked(Prefs.vote(this));
        swRoute.setChecked(Prefs.autoRoute(this));
        swExplain.setChecked(Prefs.explainOn(this));
        swSelfFirst.setChecked(Prefs.selfBankFirst(this));
        sbTh.setProgress(Prefs.thresholdPct(this));
        binding = false;
        updateThLabel(Prefs.thresholdPct(this));
        applySettingGates();
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
                // 拟人点击依附于自动点击，关掉自动点击就把拟人一起关掉，别留一个空开关
                if (!on && Prefs.humanClick(MainActivity.this)) {
                    Prefs.setHumanClick(MainActivity.this, false);
                    binding = true;
                    if (swHuman != null) swHuman.setChecked(false);
                    binding = false;
                }
                if (!on) {
                    // 自动点击关了，就不存在「答完」这个时刻，自动翻页也没意义
                    Prefs.setAutoNext(MainActivity.this, false);
                    binding = true;
                    if (swAutoNext != null) swAutoNext.setChecked(false);
                    binding = false;
                }
                applySettingGates();
            }
        });
        swHuman.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton c, boolean on) {
                if (binding) return;
                if (!on) { Prefs.setHumanClick(MainActivity.this, false); return; }
                // 默认关闭的能力，开启时必须有明确确认
                new AlertDialog.Builder(MainActivity.this, R.style.WhiteDialog)
                        .setTitle("开启拟人点击？")
                        .setMessage("点击会加入 80~200ms 随机延迟、按压 90~180ms、落点偏 ±3dp。\n"
                                + "只影响点击手感，不改变答案，也不会绕过任何限制。")
                        .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                binding = true;
                                if (swHuman != null) swHuman.setChecked(false);
                                binding = false;
                            }
                        })
                        .setPositiveButton("开启", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                Prefs.setHumanClick(MainActivity.this, true);
                            }
                        })
                        .show();
            }
        });
        swAutoNext.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton c, boolean on) {
                if (binding) return;
                if (!on) { Prefs.setAutoNext(MainActivity.this, false); return; }
                new AlertDialog.Builder(MainActivity.this, R.style.WhiteDialog)
                        .setTitle("开启自动翻页？")
                        .setMessage("每答完一题，会自动点「下一题」继续，直到找不到按钮或你中途关掉。\n\n"
                                + "它只在自动点击成功后触发；判不准、有分歧、选项对不上时不会翻页，"
                                + "留给你自己确认。")
                        .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                binding = true;
                                if (swAutoNext != null) swAutoNext.setChecked(false);
                                binding = false;
                            }
                        })
                        .setPositiveButton("开启", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                Prefs.setAutoNext(MainActivity.this, true);
                                applySettingGates();
                            }
                        })
                        .show();
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
                if (on && Prefs.vote(MainActivity.this)) {
                    Toast.makeText(MainActivity.this, "投票和复核只能二选一，已保留投票", Toast.LENGTH_SHORT).show();
                    binding = true; swDual.setChecked(false); binding = false;
                    Prefs.setDualCheck(MainActivity.this, false);
                }
            }
        });
        swVote.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton c, boolean on) {
                if (binding) return;
                if (on && Prefs.cloudReadyCount(MainActivity.this) < 2) {
                    binding = true; swVote.setChecked(false); binding = false;
                    Toast.makeText(MainActivity.this,
                            "需要 2 个以上已填 Key 的在线模型", Toast.LENGTH_LONG).show();
                    return;
                }
                Prefs.setVote(MainActivity.this, on);
                if (on) {
                    // 互斥：投票已经是对拍了，再叠复核会变成 3~4 次调用
                    Prefs.setDualCheck(MainActivity.this, false);
                    binding = true; if (swDual != null) swDual.setChecked(false); binding = false;
                    Toast.makeText(MainActivity.this, "已开启投票，复核和题型路由都不再生效", Toast.LENGTH_SHORT).show();
                }
                applySettingGates();
            }
        });
        swRoute.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton c, boolean on) {
                if (binding) return;
                Prefs.setAutoRoute(MainActivity.this, on);
            }
        });
        swExplain.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton c, boolean on) {
                if (binding) return;
                Prefs.setExplainOn(MainActivity.this, on);
            }
        });
        swSelfFirst.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton c, boolean on) {
                if (binding) return;
                Prefs.setSelfBankFirst(MainActivity.this, on);
                if (on && !SelfBankStore.hasBank(MainActivity.this)) {
                    Toast.makeText(MainActivity.this,
                            "还没导入自选题库，去「题库 → 自选题库」上传", Toast.LENGTH_LONG).show();
                }
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
                swHuman = null; swAutoNext = null; tvAutoNextHint = null;
                swVote = null; swRoute = null;
                swExplain = null; swSelfFirst = null; tvVoteHint = null; tvRouteHint = null;
                sbTh = null; tvTh = null; tvThHint = null; tvSettingsVersion = null;
                QService.resumeOverlayAfterDialog();
                refresh();
            }
        });
        QService.pauseOverlayForDialog();   // 弹窗期间先收走悬浮球，别压着开关
        showTallSheet(d);
    }

    /**
     * 开关的前置条件：
     * ─ 拟人点击依赖自动点击；
     * ─ 多模型投票需要 ≥2 个已配置的在线模型，不足时置灰并说明原因。
     */
    private void applySettingGates() {
        if (swHuman != null) {
            boolean can = Prefs.autoClick(this);
            swHuman.setEnabled(can);
            swHuman.setAlpha(can ? 1f : 0.45f);
        }
        if (swAutoNext != null) {
            boolean can = Prefs.autoClick(this);
            swAutoNext.setEnabled(can);
            swAutoNext.setAlpha(can ? 1f : 0.45f);
            if (tvAutoNextHint != null) {
                tvAutoNextHint.setTextColor(clr(can ? R.color.text_hint : R.color.warning));
                tvAutoNextHint.setText(can
                        ? "只在自动点击成功后翻页，会一题接一题连答；找不到「下一题」会停下"
                        : "需要先打开上面的「自动点击选项」，否则没有「答完」这个时刻");
            }
        }
        if (swVote != null) {
            int n = Prefs.cloudReadyCount(this);
            boolean can = n >= 2;
            swVote.setEnabled(can);
            swVote.setAlpha(can ? 1f : 0.45f);
            if (!can && Prefs.vote(this)) {
                Prefs.setVote(this, false);
                binding = true; swVote.setChecked(false); binding = false;
            }
            if (tvVoteHint != null) {
                if (can) {
                    tvVoteHint.setTextColor(clr(R.color.text_hint));
                    tvVoteHint.setText("已配置 " + n + " 个在线模型 · 最多问 3 家取多数，每题消耗 "
                            + Math.min(n, 3) + " 次；只对路由判为在线的题生效。"
                            + "按最近成功率排序，老过载的自动靠后");
                } else {
                    tvVoteHint.setTextColor(clr(R.color.warning));
                    tvVoteHint.setText("需配置 2 个以上在线大模型（当前 " + n + " 个）· 去「在线模型」里多填一家 Key");
                }
            }
        }
        applyRouteGate();
    }

    /**
     * 题型路由的有效性提示（v7.2.6）。
     *
     * <p>路由和投票在 AnswerEngine 里是同一层，投票优先 —— 两个都开时路由**静默失效**，
     * 但界面上两个开关都是亮的，用户会以为叠加生效。这里把三种「看起来开着、其实不工作」
     * 的情况说清楚：投票盖过路由、只有一条通道、两条通道都没启用。
     */
    private void applyRouteGate() {
        if (swRoute == null) return;
        boolean voting = swVote != null && swVote.isChecked();
        boolean cloudOk = Prefs.cloudOn(this) && Prefs.cloudReady(this, Prefs.provider(this));
        boolean localOk = Prefs.localOn(this)
                && (AnswerEngine.get().ready() || ModelManager.canLoad(this, Prefs.spec(this)));

        // v7.2.8：投票和路由可以共存 —— 路由先决定走哪条线，投票只作用于在线线路
        swRoute.setEnabled(true);
        swRoute.setAlpha(1f);

        if (tvRouteHint == null) return;
        String hint;
        boolean warn = false;
        if (voting) {
            hint = "投票已开启 · 路由仍生效：判为在线的题才投票（判为本地的不烧额度）";
        } else if (!cloudOk && !localOk) {
            hint = "在线和本地都没启用 · 题型路由暂不生效";
            warn = true;
        } else if (!cloudOk) {
            hint = "当前只有本地通道（在线未启用）· 题型路由暂不生效";
            warn = true;
        } else if (!localOk) {
            hint = "当前只有在线通道（本地模型未载入）· 题型路由暂不生效";
            warn = true;
        } else {
            hint = "短题/常识走本地，数学计算、文言文、长题干、多选多项走在线。"
                    + "检测到倒计时会自动提速（投票只问 2 家、4.5 秒内出结果）";
        }
        tvRouteHint.setText(hint);
        tvRouteHint.setTextColor(clr(warn ? R.color.warning : R.color.text_hint));
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
        final TextView btnExportMiss = v.findViewById(R.id.btnExportMiss);
        final TextView tvMissHint = v.findViewById(R.id.tvMissHint);
        final int missCount = MissLog.count(this);
        if (btnExportMiss != null) {
            btnExportMiss.setText(missCount > 0 ? "导出未命中题（" + missCount + " 道）" : "导出未命中题");
            btnExportMiss.setEnabled(missCount > 0);
            btnExportMiss.setAlpha(missCount > 0 ? 1f : 0.45f);
            btnExportMiss.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) { exportMiss(); }
            });
        }
        if (tvMissHint != null && missCount > 0) {
            tvMissHint.setVisibility(View.VISIBLE);
            tvMissHint.setText("这 " + missCount + " 道题自选题库里没有，全靠模型答的。导出来后可以直接"
                    + "「上传题库文件」补进去，下次遇到就不用再花额度。");
        }

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

    // ================= 题库中心（v7.1） =================

    private void showBankDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_bank, null);
        boxBank     = v.findViewById(R.id.boxBank);
        tvBankEmpty = v.findViewById(R.id.tvBankEmpty);
        tvBankCount = v.findViewById(R.id.tvBankCount);
        tvBankHint  = v.findViewById(R.id.tvBankHint);
        segAll      = v.findViewById(R.id.segAll);
        segConflict = v.findViewById(R.id.segConflict);
        segWrong    = v.findViewById(R.id.segWrong);
        btnBankSelect = v.findViewById(R.id.btnBankSelect);
        btnBankAll    = v.findViewById(R.id.btnBankAll);
        btnBankDelete = v.findViewById(R.id.btnBankDelete);
        boxBankAction = v.findViewById(R.id.boxBankAction);
        tvSelfEntryCount = v.findViewById(R.id.tvSelfEntryCount);
        tvUndoBar = v.findViewById(R.id.tvUndoBar);
        if (tvUndoBar != null) {
            tvUndoBar.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) { undoDelete(); }
            });
            if (!lastDeleted.isEmpty()) {
                tvUndoBar.setText("已删除 " + lastDeleted.size() + " 道题 · 点此撤销");
                tvUndoBar.setVisibility(View.VISIBLE);
            }
        }

        v.findViewById(R.id.btnSelfBankEntry).setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { showSelfBankDialog(); }
        });

        // 每次打开都从普通模式开始，别把上次的勾选带进来
        bankSelectMode = false;
        selectedKeys.clear();
        bankRowViews.clear();

        btnBankSelect.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { toggleBankSelectMode(); }
        });
        btnBankAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { selectAllVisible(); }
        });
        btnBankDelete.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { confirmBatchDelete(); }
        });

        segAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { bankFilter = 0; renderBank(); }
        });
        segConflict.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { bankFilter = 1; renderBank(); }
        });
        segWrong.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { bankFilter = 2; renderBank(); }
        });

        final AlertDialog d = new AlertDialog.Builder(this, R.style.WhiteDialog).setView(v).create();
        v.findViewById(R.id.btnCloseBank).setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { d.dismiss(); }
        });
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface x) {
                boxBank = null; tvBankEmpty = null; tvBankCount = null; tvBankHint = null;
                segAll = null; segConflict = null; segWrong = null;
                btnBankSelect = null; btnBankAll = null; btnBankDelete = null;
                boxBankAction = null; tvSelfEntryCount = null;
                tvUndoBar = null;
                ui.removeCallbacks(clearUndo);
                bankSelectMode = false;
                selectedKeys.clear();
                bankRowViews.clear();
                QService.resumeOverlayAfterDialog();
                refresh();
            }
        });
        QService.pauseOverlayForDialog();
        showTallSheet(d);
        renderBank();
    }

    private void paintSeg(TextView tv, boolean on) {
        if (tv == null) return;
        tv.setBackgroundResource(on ? R.drawable.seg_on : R.drawable.seg_off);
        tv.setTextColor(on ? 0xFFFFFFFF : clr(R.color.text_secondary));
    }

    private void renderBank() {
        if (boxBank == null) return;
        paintSeg(segAll, bankFilter == 0);
        paintSeg(segConflict, bankFilter == 1);
        paintSeg(segWrong, bankFilter == 2);

        List<Record.Bank> all = Record.bankList(this);
        int conflict = 0, wrong = 0;
        for (Record.Bank b : all) {
            if (b.hasConflict()) conflict++;
            if (b.wrong) wrong++;
        }
        if (tvBankCount != null) {
            tvBankCount.setText(all.size() + " 题"
                    + (conflict > 0 ? " · 分歧 " + conflict : "")
                    + (wrong > 0 ? " · 错题 " + wrong : ""));
        }
        if (tvSelfEntryCount != null) {
            int n = SelfBankStore.list(this).size();
            tvSelfEntryCount.setText(n == 0 ? "未导入" : (SelfBankStore.totalCount(this) + " 题"));
        }

        boxBank.removeAllViews();
        bankRowViews.clear();
        int shown = 0;
        for (Record.Bank b : all) {
            if (bankFilter == 1 && !b.hasConflict()) continue;
            if (bankFilter == 2 && !b.wrong) continue;
            if (shown >= 300) break;        // 规模兜底，几百条够翻
            View row = bankRow(b);
            boxBank.addView(row);
            bankRowViews.put(b.key, row);
            shown++;
        }

        if (tvBankHint != null) {
            int pending = 0;
            for (Record.Bank bb : all) if (!bb.verified && !bb.hasConflict()) pending++;
            tvBankHint.setText(bankSelectMode
                    ? "点条目多选，可跨筛选累计；「全选本页」只选当前列表已显示的题"
                    : (pending > 0
                        ? "答过的题会先标「待确认」：再答对一次或点「确认可用」后才直接给答案（现在有 "
                            + pending + " 条待确认）"
                        : "答过的题会自动记住。改过的答案是「金标准」，模型以后不会再覆盖它。"));
            tvBankHint.setTextColor(clr(bankSelectMode ? R.color.primary_deep : R.color.text_hint));
        }
        if (btnBankSelect != null) btnBankSelect.setText(bankSelectMode ? "取消" : "选择");
        updateBankActionBar();

        if (tvBankEmpty != null) {
            tvBankEmpty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
            if (shown == 0) {
                tvBankEmpty.setText(bankFilter == 0
                        ? "这里还是空的\n刷几道题后，答过的题会自动进来"
                        : (bankFilter == 1 ? "没有有分歧的题\n说明题库和模型一直给同一个答案"
                                           : "错题本是空的\n改过答案、或采纳过不同意见才会进来"));
            }
        }
    }

    private View bankRow(final Record.Bank b) {
        View item = getLayoutInflater().inflate(R.layout.item_bank, boxBank, false);
        TextView tvQ   = item.findViewById(R.id.tvBankQ);
        TextView tvAns = item.findViewById(R.id.tvBankAns);
        TextView tvMeta= item.findViewById(R.id.tvBankMeta);
        TextView tvFlag= item.findViewById(R.id.tvBankFlag);
        TextView tvCf  = item.findViewById(R.id.tvBankConflict);

        String text = b.q == null ? "" : b.q;
        if (text.isEmpty()) text = "（旧数据 · 没有存题干）";
        tvQ.setText(text);
        tvAns.setText(b.a);
        String src = (b.src == null || b.src.isEmpty()) ? "来源未知" : b.src;
        tvMeta.setText(src + " · 命中 " + b.hits + " 次" + (b.locked ? " · 已锁定" : ""));

        if (b.hasConflict()) {
            setPill(tvFlag, "分歧", R.color.warning_bg, R.color.warning);
            tvFlag.setVisibility(View.VISIBLE);
            StringBuilder sb = new StringBuilder();
            for (Record.Cf f : b.cf) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(f.a);
                if (f.src != null && !f.src.isEmpty()) sb.append("（").append(f.src).append("）");
            }
            tvCf.setText("另有答案：" + sb);
            tvCf.setVisibility(View.VISIBLE);
        } else if (b.wrong) {
            setPill(tvFlag, "错题", R.color.danger_bg, R.color.danger);
            tvFlag.setVisibility(View.VISIBLE);
        } else if (b.locked) {
            setPill(tvFlag, "已锁定", R.color.primary_soft, R.color.primary_deep);
            tvFlag.setVisibility(View.VISIBLE);
        } else if (!b.verified) {
            setPill(tvFlag, "待确认", R.color.warning_bg, R.color.warning);
            tvFlag.setVisibility(View.VISIBLE);
        } else {
            tvFlag.setVisibility(View.GONE);
        }

        TextView tvCheck = item.findViewById(R.id.tvBankCheck);
        styleBankCheck(tvCheck, bankSelectMode && selectedKeys.contains(b.key));

        item.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                if (bankSelectMode) toggleBankSelect(b.key);
                else showBankItem(b);
            }
        });
        // 长按任意条目 = 直接进多选并选中它，省一次点「选择」
        item.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View x) {
                if (!bankSelectMode) {
                    bankSelectMode = true;
                    renderBank();          // 重刷一次，勾选圈才会出现
                }
                if (!selectedKeys.contains(b.key)) toggleBankSelect(b.key);
                Toast.makeText(MainActivity.this, "已进入多选，再点其它条目累加", Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        return item;
    }

    /** 右侧指示：普通模式是「›」，多选模式是空心/实心圆点。 */
    private void styleBankCheck(TextView tv, boolean checked) {
        if (tv == null) return;
        if (!bankSelectMode) {
            tv.setText("›");
            tv.setTextColor(clr(R.color.text_light));
            return;
        }
        tv.setText(checked ? "●" : "○");
        tv.setTextColor(clr(checked ? R.color.primary : R.color.text_light));
    }

    private void toggleBankSelectMode() {
        bankSelectMode = !bankSelectMode;
        if (!bankSelectMode) selectedKeys.clear();
        renderBank();
    }

    private void toggleBankSelect(String key) {
        if (key == null) return;
        if (selectedKeys.contains(key)) selectedKeys.remove(key);
        else selectedKeys.add(key);
        View row = bankRowViews.get(key);
        if (row != null) {
            styleBankCheck((TextView) row.findViewById(R.id.tvBankCheck), selectedKeys.contains(key));
        }
        updateBankActionBar();
    }

    /** 全选/取消全选「当前列表里已显示的题」，不影响其它筛选下的勾选。 */
    private void selectAllVisible() {
        if (bankRowViews.isEmpty()) return;
        boolean all = true;
        for (String k : bankRowViews.keySet()) {
            if (!selectedKeys.contains(k)) { all = false; break; }
        }
        if (all) selectedKeys.removeAll(bankRowViews.keySet());
        else selectedKeys.addAll(bankRowViews.keySet());

        for (java.util.Map.Entry<String, View> e : bankRowViews.entrySet()) {
            styleBankCheck((TextView) e.getValue().findViewById(R.id.tvBankCheck),
                    selectedKeys.contains(e.getKey()));
        }
        updateBankActionBar();
    }

    private void updateBankActionBar() {
        if (boxBankAction != null) {
            boxBankAction.setVisibility(bankSelectMode ? View.VISIBLE : View.GONE);
        }
        if (btnBankDelete != null) {
            int n = selectedKeys.size();
            btnBankDelete.setText(n > 0 ? "删除所选 " + n : "删除所选");
            btnBankDelete.setEnabled(n > 0);
            btnBankDelete.setAlpha(n > 0 ? 1f : 0.4f);
        }
        if (btnBankAll != null) {
            int total = bankRowViews.size();
            int picked = 0;
            for (String k : bankRowViews.keySet()) if (selectedKeys.contains(k)) picked++;
            boolean all = total > 0 && picked == total;
            btnBankAll.setText(all ? "取消全选" : "全选本页");
            btnBankAll.setEnabled(total > 0);
            btnBankAll.setAlpha(total > 0 ? 1f : 0.45f);
        }
    }

    /** 删除后 8 秒内可撤销：条目还在内存里，原样写回即可。 */
    private void showUndoBar(String text) {
        ui.removeCallbacks(clearUndo);
        if (tvUndoBar == null) {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
            ui.postDelayed(clearUndo, 8000);
            return;
        }
        tvUndoBar.setText(text);
        tvUndoBar.setVisibility(View.VISIBLE);
        ui.postDelayed(clearUndo, 8000);
    }

    private void undoDelete() {
        if (lastDeleted.isEmpty()) return;
        int n = 0;
        for (Record.Bank b : lastDeleted) {
            Record.bankPut(this, b);
            n++;
        }
        lastDeleted.clear();
        ui.removeCallbacks(clearUndo);
        if (tvUndoBar != null) tvUndoBar.setVisibility(View.GONE);
        renderBank();
        refresh();
        Toast.makeText(this, "已恢复 " + n + " 道题", Toast.LENGTH_SHORT).show();
    }

    /** 批量删除：按 key 从全量题库里删，不受当前筛选限制（勾选可跨筛选累计）。 */
    private void confirmBatchDelete() {
        final int n = selectedKeys.size();
        if (n == 0) return;
        confirm("删除所选 " + n + " 道题？",
                "会从题库里移除这 " + n + " 条记录，不可恢复。这些题下次遇到会重新推理（变慢，答案可能更新）。",
                new Runnable() {
                    public void run() {
                        List<Record.Bank> all = Record.bankList(MainActivity.this);
                        List<Record.Bank> deleted = new ArrayList<>();
                        for (Record.Bank b : all) {
                            if (!selectedKeys.contains(b.key)) continue;
                            Record.bankDelete(MainActivity.this, b);
                            deleted.add(b);          // 留着内存副本，撤销时原样写回
                        }
                        selectedKeys.clear();
                        bankSelectMode = false;
                        renderBank();
                        refresh();
                        if (!deleted.isEmpty()) {
                            lastDeleted.clear();
                            lastDeleted.addAll(deleted);
                            showUndoBar("已删除 " + deleted.size() + " 道题 · 点此撤销");
                        }
                    }
                });
    }

    private void showBankItem(final Record.Bank b) {
        curBank = b;
        View v = getLayoutInflater().inflate(R.layout.dialog_bank_item, null);
        final TextView tvQ      = v.findViewById(R.id.tvItemQ);
        final TextView tvAns    = v.findViewById(R.id.tvItemAns);
        final TextView tvSrc    = v.findViewById(R.id.tvItemSrc);
        final TextView tvMeta   = v.findViewById(R.id.tvItemMeta);
        final TextView tvFlag   = v.findViewById(R.id.tvItemFlag);
        final TextView tvCfTitle= v.findViewById(R.id.tvConflictTitle);
        final TextView btnSave  = v.findViewById(R.id.btnSaveAns);
        final TextView btnWrong = v.findViewById(R.id.btnWrongToggle);
        final TextView btnDel   = v.findViewById(R.id.btnDeleteItem);
        final View boxConflict  = v.findViewById(R.id.boxConflict);
        boxLetters = v.findViewById(R.id.boxLetters);
        boxConflictList = v.findViewById(R.id.boxConflictList);
        conflictWrap = boxConflict;

        draftLetters.clear();
        for (int i = 0; i < b.a.length(); i++) draftLetters.add(String.valueOf(b.a.charAt(i)));
        // 原答案是单个字母 → 单选语义（点谁就是谁）；多个字母 → 多选语义（累加/取消）
        bankMulti = b.a.length() > 1;

        tvQ.setText((b.q == null || b.q.isEmpty()) ? "（旧数据 · 没有存题干）" : b.q);
        tvAns.setText(b.a);
        tvSrc.setText(((b.src == null || b.src.isEmpty()) ? "来源未知" : b.src)
                + "\n命中 " + b.hits + " 次" + (b.locked ? " · 已锁定" : ""));

        // 记忆里的选项原文：命中时靠它把「旧字母」翻译成「这次屏幕上的字母」。
        // 显示出来用户才知道 App 到底存了什么，也才能判断一条记忆可不可信。
        TextView tvOpts = v.findViewById(R.id.tvItemOpts);
        if (tvOpts != null) {
            tvOpts.setVisibility(View.VISIBLE);
            if (b.opts.isEmpty()) {
                tvOpts.setTextColor(clr(R.color.warning));
                tvOpts.setText("这条记忆是旧版本存的，没有选项内容 —— 下次遇到会重新记录；"
                        + "在此之前只能按字母给答案，不会自动点击。");
            } else {
                tvOpts.setTextColor(clr(R.color.text_hint));
                StringBuilder sb2 = new StringBuilder("记忆的选项（命中时按内容认选项，顺序变了也能对上）");
                for (java.util.Map.Entry<String, String> e : b.opts.entrySet()) {
                    sb2.append('\n').append(e.getKey()).append(". ").append(e.getValue());
                }
                if (b.optStale > 0) {
                    sb2.append("\n\n最近有 ").append(b.optStale).append(" 次选项对不上屏幕，"
                            + (b.optStale >= 2 ? "已打回待确认" : "再对不上一次就会打回待确认"));
                }
                tvOpts.setText(sb2.toString());
            }
        }

        String when = b.ts > 0
                ? new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                        .format(new java.util.Date(b.ts))
                : "未知";
        tvMeta.setText("最近更新 " + when + " · 编号 " + shortKey(b.key));

        if (b.locked)      setPill(tvFlag, "已锁定", R.color.primary_soft, R.color.primary_deep);
        else if (b.wrong)  setPill(tvFlag, "错题", R.color.danger_bg, R.color.danger);
        else if (b.hasConflict()) setPill(tvFlag, "分歧", R.color.warning_bg, R.color.warning);
        else if (!b.verified) setPill(tvFlag, "待确认", R.color.warning_bg, R.color.warning);
        else tvFlag.setVisibility(View.GONE);
        if (b.locked || b.wrong || b.hasConflict() || !b.verified) tvFlag.setVisibility(View.VISIBLE);

        final TextView btnVerify = v.findViewById(R.id.btnVerifyAns);
        if (btnVerify != null) {
            btnVerify.setVisibility(b.verified ? View.GONE : View.VISIBLE);
            btnVerify.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    Record.bankConfirm(MainActivity.this, b);
                    btnVerify.setVisibility(View.GONE);
                    tvFlag.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, "已确认，下次遇到直接给答案", Toast.LENGTH_SHORT).show();
                    renderBank();
                    refresh();
                }
            });
        }

        renderLetterPick(tvAns);
        renderConflicts(b, boxConflict, tvCfTitle, tvAns, tvSrc);
        btnWrong.setText(b.wrong ? "取消错题标记" : "标为错题");

        final AlertDialog d = new AlertDialog.Builder(this, R.style.WhiteDialog).setView(v).create();

        btnSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                String letters = joinLetters();
                if (letters.isEmpty()) {
                    Toast.makeText(MainActivity.this, "至少选一个字母", Toast.LENGTH_SHORT).show();
                    return;
                }
                Record.bankSaveManual(MainActivity.this, b, letters);
                Toast.makeText(MainActivity.this, "已保存，这道题的答案已锁定", Toast.LENGTH_SHORT).show();
                d.dismiss();
                renderBank();
                refresh();
            }
        });
        btnWrong.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                Record.bankSetWrong(MainActivity.this, b, !b.wrong);
                Toast.makeText(MainActivity.this, b.wrong ? "已标为错题" : "已取消错题标记",
                        Toast.LENGTH_SHORT).show();
                btnWrong.setText(b.wrong ? "取消错题标记" : "标为错题");
                renderBank();
            }
        });
        btnDel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                confirm("删除这道题？", "题库里这条记录会被移除，下次遇到要重新推理。", new Runnable() {
                    public void run() {
                        Record.bankDelete(MainActivity.this, b);
                        d.dismiss();
                        renderBank();
                        refresh();
                    }
                });
            }
        });
        v.findViewById(R.id.btnCloseItem).setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { d.dismiss(); }
        });
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface x) {
                boxLetters = null; boxConflictList = null; curBank = null;
                conflictWrap = null;
                QService.resumeOverlayAfterDialog();
                renderBank();
                refresh();
            }
        });
        QService.pauseOverlayForDialog();
        showSheet(d);
        if (d.getWindow() != null) {
            d.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.9f),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    /** 答案选择器：A~F 可点，多选就多点几个。 */
    private void renderLetterPick(final TextView preview) {
        if (boxLetters == null) return;
        boxLetters.removeAllViews();
        for (char c = 'A'; c <= 'F'; c++) {
            final String L = String.valueOf(c);
            final TextView t = new TextView(this);
            t.setText(L);
            t.setTextSize(14);
            t.setTypeface(null, android.graphics.Typeface.BOLD);
            t.setGravity(android.view.Gravity.CENTER);
            t.setClickable(true);
            t.setFocusable(true);
            t.setContentDescription("选项 " + L);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
            lp.setMarginEnd(dp(6));
            t.setLayoutParams(lp);
            styleLetter(t, draftLetters.contains(L));
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    if (draftLetters.contains(L)) {
                        draftLetters.remove(L);
                    } else {
                        // 单选条目：点另一个字母就是"改成它"，先清空再加，别拼成 BD
                        if (!bankMulti) draftLetters.clear();
                        draftLetters.add(L);
                        java.util.Collections.sort(draftLetters);
                    }
                    syncLetterStyles();
                    if (preview != null) preview.setText(joinLetters());
                }
            });
            boxLetters.addView(t);
        }
    }

    private void styleLetter(TextView t, boolean on) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(on ? clr(R.color.primary) : clr(R.color.bg_field));
        if (!on) g.setStroke(dp(1), clr(R.color.stroke));
        t.setBackground(g);
        t.setTextColor(on ? 0xFFFFFFFF : clr(R.color.text_secondary));
    }

    /** 逐个字母重刷选中态（单选切换时旧的选中必须一起灭掉）。 */
    private void syncLetterStyles() {
        if (boxLetters == null) return;
        for (int i = 0; i < boxLetters.getChildCount(); i++) {
            View c = boxLetters.getChildAt(i);
            if (c instanceof TextView) {
                TextView tv = (TextView) c;
                styleLetter(tv, draftLetters.contains(tv.getText().toString()));
            }
        }
    }

    private String joinLetters() {
        StringBuilder sb = new StringBuilder();
        for (String s : draftLetters) sb.append(s);
        return sb.toString();
    }

    /** 冲突区：列出题库答案 + 每个不同答案，各自一个「采纳」。 */
    private void renderConflicts(final Record.Bank b, View wrap, TextView title,
                                 final TextView preview, final TextView srcView) {
        if (wrap == null || boxConflictList == null) return;
        boxConflictList.removeAllViews();
        if (!b.hasConflict()) { wrap.setVisibility(View.GONE); return; }
        wrap.setVisibility(View.VISIBLE);
        title.setText("有 " + b.cf.size() + " 个不同答案");
        addConflictRow(b.a, (b.src == null || b.src.isEmpty() ? "当前答案" : b.src), false, b, preview, srcView);
        for (Record.Cf f : b.cf) {
            addConflictRow(f.a, (f.src == null || f.src.isEmpty() ? "模型" : f.src), true, b, preview, srcView);
        }
    }

    private void addConflictRow(final String letters, String src, boolean adoptable,
                               final Record.Bank b, final TextView preview, final TextView srcView) {
        if (boxConflictList == null) return;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.model_item_bg);
        row.setPadding(dp(12), dp(9), dp(10), dp(9));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rl.topMargin = dp(6);
        row.setLayoutParams(rl);

        TextView ans = new TextView(this);
        ans.setText(letters);
        ans.setTextSize(17);
        ans.setTypeface(null, android.graphics.Typeface.BOLD);
        ans.setTextColor(clr(adoptable ? R.color.primary_deep : R.color.text_primary));
        row.addView(ans);

        TextView sub = new TextView(this);
        sub.setText(src + (adoptable ? "" : " · 当前采用"));
        sub.setTextSize(11);
        sub.setTextColor(clr(R.color.text_hint));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        sl.leftMargin = dp(10);
        row.addView(sub, sl);

        if (adoptable) {
            TextView act = new TextView(this);
            act.setText("采纳");
            act.setTextSize(12);
            act.setGravity(android.view.Gravity.CENTER);
            act.setPadding(dp(14), dp(5), dp(14), dp(5));
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setCornerRadius(dp(100));
            g.setColor(clr(R.color.primary));
            act.setBackground(g);
            act.setTextColor(0xFFFFFFFF);
            act.setClickable(true);
            act.setFocusable(true);
            act.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    Record.bankSaveAdopt(MainActivity.this, b, letters, src);
                    if (preview != null) preview.setText(letters);
                    if (srcView != null) srcView.setText("采纳 " + letters + "\n命中 " + b.hits + " 次 · 已锁定");
                    Toast.makeText(MainActivity.this, "已采纳 " + letters, Toast.LENGTH_SHORT).show();
                    if (boxConflictList != null) boxConflictList.removeAllViews();
                    if (conflictWrap != null) conflictWrap.setVisibility(View.GONE);
                    renderBank();
                    refresh();
                }
            });
            row.addView(act);
        }

        boxConflictList.addView(row);
    }

    private String shortKey(String key) {
        if (key == null || key.length() < 6) return "-";
        return key.substring(0, 6);
    }


    // ================= 自选题库（v7.2） =================

    private void showSelfBankDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_selfbank, null);
        boxSelf     = v.findViewById(R.id.boxSelf);
        tvSelfEmpty = v.findViewById(R.id.tvSelfEmpty);
        tvSelfCount = v.findViewById(R.id.tvSelfCount);

        v.findViewById(R.id.btnImport).setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { pickBankFile(); }
        });

        final AlertDialog d = new AlertDialog.Builder(this, R.style.WhiteDialog).setView(v).create();
        v.findViewById(R.id.btnCloseSelf).setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { d.dismiss(); }
        });
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface x) {
                boxSelf = null; tvSelfEmpty = null; tvSelfCount = null;
                if (boxBank != null) renderBank();
                QService.resumeOverlayAfterDialog();
            }
        });
        QService.pauseOverlayForDialog();   // 悬浮球会压住说明文字，弹窗期间先收走
        showTallSheet(d);
        renderSelfList();
    }

    private void renderSelfList() {
        if (boxSelf == null) return;
        boxSelf.removeAllViews();
        List<SelfBankStore.Entry> list = SelfBankStore.list(this);
        int total = 0;
        for (SelfBankStore.Entry e : list) total += e.count;
        if (tvSelfCount != null) {
            tvSelfCount.setText(list.isEmpty() ? "未导入" : (list.size() + " 份 · " + total + " 题"));
        }
        if (tvSelfEmpty != null) {
            tvSelfEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        }
        for (final SelfBankStore.Entry e : list) {
            View item = getLayoutInflater().inflate(R.layout.item_selfbank, boxSelf, false);
            TextView name = item.findViewById(R.id.tvSelfName);
            TextView meta = item.findViewById(R.id.tvSelfMeta);
            TextView del  = item.findViewById(R.id.tvSelfDel);
            name.setText(e.lost ? (e.name + "（文件丢失）") : e.name);
            if (e.lost) {
                name.setTextColor(clr(R.color.warning));
                meta.setText("原 " + e.count + " 题 · 文件已不存在，请重新导入");
                meta.setTextColor(clr(R.color.warning));
            } else {
                String when = e.ts > 0
                        ? new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                                .format(new java.util.Date(e.ts))
                        : "";
                meta.setText(e.count + " 题" + (when.isEmpty() ? "" : " · " + when + " 导入"));
            }
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) { confirmDeleteSelf(e); }
            });
            boxSelf.addView(item);
        }
    }

    private void confirmDeleteSelf(final SelfBankStore.Entry e) {
        confirm("删除「" + e.name + "」？",
                "会移除这 " + e.count + " 道自选题库里的题，不可恢复。",
                new Runnable() {
                    public void run() {
                        SelfBankStore.remove(MainActivity.this, e.id);
                        renderSelfList();
                        if (boxBank != null) renderBank();
                        Toast.makeText(MainActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    /** 用系统文件选择器（SAF）：不需要任何存储权限。 */
    private void pickBankFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel",
                "text/csv",
                "text/comma-separated-values",
                "text/tab-separated-values",
                "text/plain",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/json"
        });
        try {
            Log.i(TAG, "打开文件选择器");
            startActivityForResult(i, REQ_IMPORT);
        } catch (Throwable t) {
            Log.w(TAG, "打不开文件选择器", t);
            Toast.makeText(this, "打不开文件选择器：" + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 导出未命中题。
     *
     * <p>Android 10 起直接写「下载/AI答题/」：一次点击就完事，不用在文件选择器里翻找。
     * 老系统退回系统「另存为」，同样不需要存储权限。
     */
    private void exportMiss() {
        int n = MissLog.exportableCount(MissLog.read(this));
        if (n == 0) {
            Toast.makeText(this, "还没有未命中的题", Toast.LENGTH_SHORT).show();
            return;
        }
        String stamp = new java.text.SimpleDateFormat("MMdd-HHmm", java.util.Locale.CHINA)
                .format(new java.util.Date());
        String name = "未命中题-" + stamp + ".txt";

        if (Build.VERSION.SDK_INT >= 29 && writeToDownloads(name) > 0) {
            Toast.makeText(this, "已导出 " + n + " 道题到「下载/AI答题/" + name + "」",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(i, REQ_EXPORT_MISS);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开保存对话框", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 直接写进「下载/AI答题/」。
     *
     * @return 写入的字节数；0 表示没成功（调用方退回另存为）
     */
    private int writeToDownloads(String name) {
        Uri uri = null;
        try {
            String text = MissLog.exportText(this);
            byte[] data = text.getBytes("UTF-8");
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AI答题");
            uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new java.io.IOException("系统不给建文件");
            java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt");
            if (out == null) throw new java.io.IOException("打不开输出流");
            out.write(data);
            out.flush();
            out.close();
            return data.length;
        } catch (Throwable t) {
            Log.w(TAG, "写入下载目录失败，退回另存为", t);
            if (uri != null) {
                try { getContentResolver().delete(uri, null, null); } catch (Throwable ignored) {}
            }
            return 0;
        }
    }

    private void writeMissTo(Uri uri) {
        try {
            String text = MissLog.exportText(this);
            java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt");
            if (out == null) throw new java.io.IOException("写不进这个位置");
            out.write(text.getBytes("UTF-8"));
            out.flush();
            out.close();
            Toast.makeText(this, "已导出，可直接上传回题库", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Log.w(TAG, "导出未命中题失败", t);
            Toast.makeText(this, "导出失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        if (req == REQ_EXPORT_MISS) {
            writeMissTo(data.getData());
            return;
        }
        if (req != REQ_IMPORT) return;
        final Uri uri = data.getData();
        final String name = displayNameOf(uri);
        Toast.makeText(this, "正在解析 " + name + "…", Toast.LENGTH_SHORT).show();

        new Thread(new Runnable() {
            public void run() {
                java.io.File tmp = null;
                try {
                    tmp = new java.io.File(getCacheDir(), "import_bank.dat");
                    java.io.InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new java.io.IOException("读不到文件");
                    java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.close();
                    in.close();

                    final Importer.Result r = Importer.importFile(tmp, name);
                    SelfBankStore.add(MainActivity.this, name, r.json);
                    ui.post(new Runnable() {
                        public void run() {
                            renderSelfList();
                            if (boxBank != null) renderBank();
                            showImportResult(name, r);
                        }
                    });
                } catch (final Throwable t) {
                    Log.w(TAG, "题库导入失败", t);
                    ui.post(new Runnable() {
                        public void run() {
                            new AlertDialog.Builder(MainActivity.this, R.style.WhiteDialog)
                                    .setTitle("导入失败")
                                    .setMessage(String.valueOf(t.getMessage()))
                                    .setPositiveButton("知道了", null)
                                    .show();
                        }
                    });
                } finally {
                    if (tmp != null && tmp.exists()) tmp.delete();
                }
            }
        }).start();
    }

    /** 导入完成先给识别概况，让用户确认「这份文档被理解成了什么」再入库。 */
    private void showImportResult(String name, Importer.Result r) {
        String msg = "文件：" + name
                + "\n格式：" + r.format
                + "\n识别：" + r.summary()
                + "\n\n已加入自选题库，下次遇到这些题会直接命中（不走模型）。";
        new AlertDialog.Builder(this, R.style.WhiteDialog)
                .setTitle("导入完成")
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 文件显示名：优先用 SAF 的 DISPLAY_NAME（Importer 靠后缀判断格式）。 */
    private String displayNameOf(Uri uri) {
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) {
                            String s = c.getString(idx);
                            if (s != null && !s.isEmpty()) return s;
                        }
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {}
        String s = uri.getLastPathSegment();
        return (s == null || s.isEmpty()) ? "题库" : s;
    }

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

    /** 阈值标签 + 一行人话解释：百分比本身对用户没有意义，要说清「多自信才动手」。 */
    private void updateThLabel(int p) {
        if (tvTh != null) tvTh.setText("多自信才自动点击 · " + p + "%");
        if (tvThHint == null) return;
        String mood;
        if (p <= 40)      mood = "很宽松：模型稍有把握就点，题简单或题库固定时用";
        else if (p <= 70) mood = "平衡：多数题自动点，明显没把握时只显示不点";
        else              mood = "很保守：只有很有把握才点，其余留给你自己判断";
        tvThHint.setText("模型把握低于 " + p + "% 时不自动点击，只显示答案。\n" + mood);
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
