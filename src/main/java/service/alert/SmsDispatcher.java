package service.alert;

import common.constant.CommonConstant;
import entity.AlertRecord;
import entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.AlertDecisionMapper;
import mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** 在告警事务提交后处理短信。模拟发送和真实发送使用不同状态。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmsDispatcher {
    private final AlertDecisionMapper mapper;
    private final UserMapper userMapper;
    private final SmsSender sender;

    public void dispatch(List<AlertRecord> alerts) {
        for (AlertRecord alert : alerts) {
            if (mapper.claimSms(alert.getId()) != 1) { continue; }
            String status;
            try {
                User user = alert.getDriverId() == null ? null : userMapper.selectById(alert.getDriverId());
                if (user == null || !CommonConstant.ROLE_DRIVER.equals(user.getRole())
                        || !Boolean.TRUE.equals(user.getEnabled())
                        || user.getPhone() == null || user.getPhone().isBlank()) {
                    status = "SKIPPED_NO_RECIPIENT";
                } else if (user.getVehicleId() != null && !Objects.equals(user.getVehicleId(), alert.getVehicleId())) {
                    status = "SKIPPED_RECIPIENT_MISMATCH";
                } else {
                    SmsSender.Delivery delivery = sender.send(alert, user.getPhone());
                    status = delivery == null ? "FAILED" : delivery.name();
                }
            } catch (Exception e) {
                status = "FAILED";
                log.error("短信处理失败：alertId={}，异常类型={}", alert.getId(), e.getClass().getSimpleName());
            }
            mapper.updateSmsStatus(alert.getId(), status);
            log.info("短信处理状态：alertId={}，status={}", alert.getId(), status);
        }
    }
}
