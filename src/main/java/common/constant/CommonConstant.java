package common.constant;

import java.util.Set;

/**
 * 全局常量（M0 填充空壳）。
 * 只放"跨模块共用、且短期内不会变"的取值，避免各模块各写一套字符串。
 */
public final class CommonConstant {

    private CommonConstant() {
    }

    // ==================== 角色 ====================

    /** 平台管理员：可配置全局阈值，看全部车队 */
    public static final String ROLE_PLATFORM_ADMIN = "PLATFORM_ADMIN";

    /** 车队管理员：只管本车队 */
    public static final String ROLE_FLEET_ADMIN = "FLEET_ADMIN";

    /** 司机 */
    public static final String ROLE_DRIVER = "DRIVER";

    // ==================== 账号状态 ====================

    public static final int STATUS_DISABLED = 0;

    public static final int STATUS_ENABLED = 1;

    // ==================== 告警记录类型 ====================

    /** 短信告警（发短信 + 后台记录） */
    public static final String RECORD_TYPE_SMS_ALERT = "SMS_ALERT";

    /** 后台记录（只记录，不发短信） */
    public static final String RECORD_TYPE_BACKLOG = "BACKLOG";

    // ==================== 行为类别 ====================
    // 取值与 FastAPI 返回的 class_name 完全一致（A 已确认直接返回 "c0"~"c9"）

    /** c0 正常驾驶 */
    public static final String BEHAVIOR_C0 = "c0";
    /** c1 右手发短信（短信告警类） */
    public static final String BEHAVIOR_C1 = "c1";
    /** c2 右手打电话（后台记录） */
    public static final String BEHAVIOR_C2 = "c2";
    /** c3 左手发短信（短信告警类） */
    public static final String BEHAVIOR_C3 = "c3";
    /** c4 左手打电话（后台记录） */
    public static final String BEHAVIOR_C4 = "c4";
    /** c5 操作中控台（后台记录） */
    public static final String BEHAVIOR_C5 = "c5";
    /** c6 喝水（后台记录） */
    public static final String BEHAVIOR_C6 = "c6";
    /** c7 向后伸手（后台记录） */
    public static final String BEHAVIOR_C7 = "c7";
    /** c8 整理头发/化妆（后台记录） */
    public static final String BEHAVIOR_C8 = "c8";
    /** c9 与乘客交谈（后台记录） */
    public static final String BEHAVIOR_C9 = "c9";

    /**
     * 触发短信告警的类别：只有 c1/c3（玩手机类）。
     * 其余 c2、c4~c9 一律走后台记录，不发短信。
     */
    public static final Set<String> SMS_ALERT_BEHAVIORS = Set.of(BEHAVIOR_C1, BEHAVIOR_C3);

    /** 是否属于"短信告警类"行为 */
    public static boolean isSmsAlertBehavior(String behavior) {
        return behavior != null && SMS_ALERT_BEHAVIORS.contains(behavior);
    }

    // ==================== 司机安全评分 ====================

    /** 初始分 / 每月重置分 */
    public static final int SCORE_INIT = 100;

    /** 短信告警每条扣分 */
    public static final int SCORE_DEDUCT_SMS_ALERT = 5;

    /** 后台记录每条扣分 */
    public static final int SCORE_DEDUCT_BACKLOG = 1;

    /** 评分等级：≥90 优，75~89 良，60~74 一般，<60 差 */
    public enum ScoreLevel {

        EXCELLENT("优", 90),
        GOOD("良", 75),
        FAIR("一般", 60),
        POOR("差", 0);

        private final String label;
        private final int minScore;

        ScoreLevel(String label, int minScore) {
            this.label = label;
            this.minScore = minScore;
        }

        public String getLabel() {
            return label;
        }

        public int getMinScore() {
            return minScore;
        }

        /** 按分数映射等级；分数为空时按满分处理 */
        public static ScoreLevel fromScore(Integer score) {
            int value = score == null ? SCORE_INIT : score;
            if (value >= EXCELLENT.minScore) {
                return EXCELLENT;
            }
            if (value >= GOOD.minScore) {
                return GOOD;
            }
            if (value >= FAIR.minScore) {
                return FAIR;
            }
            return POOR;
        }
    }
}
