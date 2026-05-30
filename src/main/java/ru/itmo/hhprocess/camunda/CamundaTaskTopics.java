package ru.itmo.hhprocess.camunda;

public final class CamundaTaskTopics {
    public static final String CREATE_APPLICATION = "create-application";
    public static final String PUBLISH_SCREENING_REQUEST = "publish-screening-request";
    public static final String SAVE_SCREENING_RESULT = "save-screening-result";
    public static final String REJECT_APPLICATION = "reject-application";
    public static final String RESERVE_INTERVIEW_SLOT = "reserve-interview-slot";
    public static final String RECORD_CANDIDATE_RESPONSE = "record-candidate-response";
    public static final String CLOSE_BY_TIMEOUT = "close-by-timeout";
    public static final String EXPORT_INTERVIEW_TO_EIS = "export-interview-to-eis";
    public static final String CREATE_VACANCY = "create-vacancy";
    public static final String UPDATE_VACANCY = "update-vacancy";
    public static final String CLOSE_VACANCY = "close-vacancy";
    public static final String CANCEL_INTERVIEW = "cancel-interview";

    private CamundaTaskTopics() {
    }
}
