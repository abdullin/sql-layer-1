package com.akiban.cserver.service.logging;

public interface AkibanLogger {
    public enum Level {
        TRACE, DEBUG, INFO, WARN, ERROR, FATAL
    }

    boolean isTraceEnabled();
    boolean isDebugEnabled();
    boolean isWarnEnabled();
    boolean isInfoEnabled();
    boolean isErrorEnabled();
    boolean isFatalEnabled();
    
    void trace(String message);
    void trace(String message, Throwable cause);
    void debug(String message);
    void debug(String message, Throwable cause);
    void warn(String message);
    void warn(String message, Throwable cause);
    void info(String message);
    void info(String message, Throwable cause);
    void error(String message);
    void error(String message, Throwable cause);
    void fatal(String message);
    void fatal(String message, Throwable cause);
}
