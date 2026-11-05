/**
 * 告警业务包（M0 只占位，不写实现）。
 *
 * M1 会在这里放告警判定的 InferResultListener 实现：
 * 滑动窗口、连续 5 帧、1 小时累计 30 次、车速门控、写告警表。
 * A 已确认由 B 实现，且 A 不在 inferworker 包里加判定逻辑。
 */
package service.alert;
