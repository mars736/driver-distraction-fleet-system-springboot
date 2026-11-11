package service.alert;

import entity.AlertRecord;

public interface SmsSender {
    /** 真实通道应以 alert.id 作为幂等键；不得把模拟结果返回为 SENT。 */
    Delivery send(AlertRecord alert, String phone);

    enum Delivery { SIMULATED, SENT }
}
