package ru.slie.luna.maven.docs;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

public class I18nResolver {
    private final ResourceBundle bundle;

    public I18nResolver(String bundleName) {
        this.bundle = ResourceBundle.getBundle(bundleName);
    }

    public I18nResolver(String bundleName, Locale locale, ClassLoader classLoader) {
        this.bundle = ResourceBundle.getBundle(bundleName, locale, classLoader);
    }

    public String t(String message, Object ...args) {
        if (args.length > 0) {
            return MessageFormat.format(bundle.getString(message), args);
        }

        return bundle.getString(message);
    }

    public boolean contains(String key) {
        return bundle.containsKey(key);
    }
}
