package service.alert.sms;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.QuerySendDetailsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "sms.mode", havingValue = "aliyun")
public class AlibabaSmsChannel {
    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES = 4;
    private static final Set<String> REJECTED = Set.of("isv.MOBILE_NUMBER_ILLEGAL", "isv.SMS_SIGNATURE_ILLEGAL",
            "isv.SMS_TEMPLATE_ILLEGAL", "isv.TEMPLATE_MISSING_PARAMETERS", "isv.BUSINESS_LIMIT_CONTROL",
            "isv.OUT_OF_SERVICE", "isv.AMOUNT_NOT_ENOUGH", "isv.BLACK_KEY_CONTROL_LIMIT");

    public enum State { SENT, FAILED, SEND_UNKNOWN, DELIVERED, DELIVERY_FAILED }

    public record Receipt(State state, String messageId, String code) { }

    private final Client client;
    private final ObjectMapper json;
    private final SmsChannelProperties.Aliyun settings;

    public AlibabaSmsChannel(SmsChannelProperties properties, ObjectMapper json) throws Exception {
        this.settings = properties.getAliyun();
        this.json = json;
        if (!required(settings.getOutIdPrefix(), "out-id-prefix").matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("sms.aliyun.out-id-prefix 格式无效");
        }
        required(settings.getSignName(), "sign-name");
        required(settings.getTemplateCode(), "template-code");
        verifyParameterNames();
        this.client = new Client(new Config()
                .setAccessKeyId(required(settings.getAccessKeyId(), "access-key-id"))
                .setAccessKeySecret(required(settings.getAccessKeySecret(), "access-key-secret"))
                .setEndpoint("dysmsapi.aliyuncs.com").setProtocol("HTTPS"));
    }

    public Receipt send(SmsDeliveryJob job, String phone) throws Exception {
        var request = new SendSmsRequest().setPhoneNumbers(phone).setSignName(settings.getSignName())
                .setTemplateCode(settings.getTemplateCode()).setOutId(job.outId());
        var parameters = new LinkedHashMap<String, String>();
        if (configured(settings.getBehaviorParameter())) {
            parameters.put(settings.getBehaviorParameter(), "检测到驾驶时操作手机");
        }
        if (configured(settings.getAlertIdParameter())) {
            parameters.put(settings.getAlertIdParameter(), Long.toString(job.alertId()));
        }
        if (!parameters.isEmpty()) { request.setTemplateParam(json.writeValueAsString(parameters)); }

        var response = client.sendSmsWithOptions(request, options()).getBody();
        if (response == null) { return new Receipt(State.SEND_UNKNOWN, null, "EMPTY_RESPONSE"); }
        String code = safeCode(response.getCode());
        if ("OK".equals(code)) { return new Receipt(State.SENT, response.getBizId(), code); }
        return new Receipt(code != null && REJECTED.contains(code) ? State.FAILED : State.SEND_UNKNOWN, null, code);
    }

    public Receipt query(SmsDeliveryJob job, LocalDate date) throws Exception {
        for (long page = 1; page <= MAX_PAGES; page++) {
            var request = new QuerySendDetailsRequest().setPhoneNumber(job.recipient())
                    .setSendDate(date.format(DateTimeFormatter.BASIC_ISO_DATE))
                    .setCurrentPage(page).setPageSize((long) PAGE_SIZE);
            if (configured(job.providerMessageId())) { request.setBizId(job.providerMessageId()); }
            var body = client.querySendDetailsWithOptions(request, options()).getBody();
            if (body == null || !"OK".equals(body.getCode()) || body.getSmsSendDetailDTOs() == null) { return null; }
            var rows = body.getSmsSendDetailDTOs().getSmsSendDetailDTO();
            if (rows == null || rows.isEmpty()) { return null; }
            for (var row : rows) {
                if (!Objects.equals(job.outId(), row.getOutId())) { continue; }
                return switch (row.getSendStatus() == null ? 0 : row.getSendStatus().intValue()) {
                    case 2 -> new Receipt(State.DELIVERY_FAILED, null, safeCode(row.getErrCode()));
                    case 3 -> new Receipt(State.DELIVERED, null, safeCode(row.getErrCode()));
                    default -> new Receipt(State.SENT, null, "WAITING_FOR_RECEIPT");
                };
            }
            if (rows.size() < PAGE_SIZE) { return null; }
        }
        return null;
    }

    private RuntimeOptions options() {
        return new RuntimeOptions().setAutoretry(false).setConnectTimeout(2000).setReadTimeout(5000);
    }

    private String safeCode(String code) {
        return code != null && code.matches("[A-Za-z0-9_.-]{1,80}") ? code : null;
    }

    private void verifyParameterNames() {
        for (String name : new String[]{settings.getBehaviorParameter(), settings.getAlertIdParameter()}) {
            if (configured(name) && !name.matches("[A-Za-z][A-Za-z0-9_]{0,31}")) {
                throw new IllegalArgumentException("短信模板变量名无效");
            }
        }
        if (configured(settings.getBehaviorParameter())
                && settings.getBehaviorParameter().equals(settings.getAlertIdParameter())) {
            throw new IllegalArgumentException("短信模板变量名必须不同");
        }
    }

    private static boolean configured(String value) { return value != null && !value.isBlank(); }

    private static String required(String value, String key) {
        if (!configured(value)) { throw new IllegalArgumentException("未配置 sms.aliyun." + key); }
        return value;
    }
}
