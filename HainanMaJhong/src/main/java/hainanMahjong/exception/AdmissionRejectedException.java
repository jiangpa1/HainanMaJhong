package hainanMahjong.exception;

/** Expected overload response; handled without WARN logs or stack traces per rejected request. */
public class AdmissionRejectedException extends RuntimeException {
    private final int retryAfterSeconds;

    public AdmissionRejectedException(int retryAfterSeconds) {
        super("当前请求较多，请在 " + retryAfterSeconds + " 秒后重试", null, false, false);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() { return retryAfterSeconds; }
}
