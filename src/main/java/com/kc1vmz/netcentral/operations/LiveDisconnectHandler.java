package com.kc1vmz.netcentral.operations;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.DisconnectedClientHelper;

/** Only suppress recognized transport disconnects on the live SSE request. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LiveDisconnectHandler implements HandlerExceptionResolver {
    public static final String LIVE_STREAM = LiveDisconnectHandler.class.getName();

    static boolean disconnected(Throwable error) {
        if (DisconnectedClientHelper.isClientDisconnectedException(error)) return true;
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof IOException && "An established connection was aborted by the software in your host machine".equals(cause.getMessage()))
                return true;
        }
        return false;
    }

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception error) {
        if (!Boolean.TRUE.equals(request.getAttribute(LIVE_STREAM)) || !disconnected(error)) return null;
        LoggerFactory.getLogger(LiveDisconnectHandler.class).debug("Live-view client disconnected: {}", error.toString());
        return new ModelAndView();
    }
}
