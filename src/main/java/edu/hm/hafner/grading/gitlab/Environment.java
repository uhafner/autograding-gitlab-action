package edu.hm.hafner.grading.gitlab;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import edu.hm.hafner.util.FilteredLog;
import edu.umd.cs.findbugs.annotations.CheckForNull;

/**
 * Simple utility to access environment variables.
 *
 * @author Ullrich Hafner
 */
class Environment {
    private final FilteredLog log;

    Environment(final FilteredLog log) {
        this.log = log;
    }

    int getInteger(final String key) {
        var value = StringUtils.defaultString(read(key));
        try {
            var integer = Integer.parseInt(value);
            log.logInfo(">>>> %s: %s", key, integer);
            return integer;
        }
        catch (NumberFormatException exception) {
            if (StringUtils.isBlank(value)) {
                log.logInfo(">>>> %s: not set", key);
            }
            else {
                log.logError(">>>> Error: no valid integer value in environment variable key %s: %s", key, value);
            }

            return Integer.MAX_VALUE;
        }
    }

    String getString(final String key) {
        String value = read(key);
        if (StringUtils.isBlank(value)) {
            log.logInfo(">>>> %s: not set", key);
        }
        else {
            log.logInfo(">>>> %s: %s", key, value);
        }
        return StringUtils.defaultString(value);
    }

    boolean getBoolean(final String name) {
        var value = StringUtils.defaultString(read(name));
        var defined = StringUtils.isNotBlank(value) && !Strings.CI.equals(value, "false");
        log.logInfo(">>>> %s: %b", name, defined);
        return defined;
    }

    @CheckForNull
    private String read(final String key) {
        return System.getenv(key);
    }
}
