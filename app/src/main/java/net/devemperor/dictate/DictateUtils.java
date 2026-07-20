package net.devemperor.dictate;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

import java.io.File;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.devemperor.dictate.ai.prompt.PromptTemplates;
import net.devemperor.dictate.preferences.DictatePrefsKt;
import net.devemperor.dictate.preferences.Pref;

public class DictateUtils {

    /** @deprecated Use {@link PromptTemplates#SYSTEM_PROMPT_BE_PRECISE} instead. */
    @Deprecated
    public static final String PROMPT_REWORDING_BE_PRECISE = PromptTemplates.SYSTEM_PROMPT_BE_PRECISE;

    public static String getAssetLanguageSuffix() {
        Locale overrideLocale = null;
        LocaleListCompat appLocales = AppCompatDelegate.getApplicationLocales();
        if (!appLocales.isEmpty()) {
            overrideLocale = appLocales.get(0);
        }
        String language = overrideLocale != null ? overrideLocale.getLanguage() : Locale.getDefault().getLanguage();
        switch (language) {
            case "de":
                return "de";
            case "es":
                return "es";
            case "pt":
                return "pt";
            default:
                return "en";
        }
    }

    public static void applyApplicationLocale(Context context) {
        SharedPreferences sp = context.getSharedPreferences("net.devemperor.dictate", Context.MODE_PRIVATE);
        String language = DictatePrefsKt.get(sp, Pref.AppLanguage.INSTANCE);
        applyApplicationLocale(language);
    }

    public static void applyApplicationLocale(String language) {
        LocaleListCompat locales;
        if (language == null || language.equals("system")) {
            locales = LocaleListCompat.getEmptyLocaleList();
        } else {
            locales = LocaleListCompat.create(new Locale(language));
        }
        LocaleListCompat current = AppCompatDelegate.getApplicationLocales();
        if (current.equals(locales)) {
            return;
        }
        AppCompatDelegate.setApplicationLocales(locales);
    }

    public static long getAudioDuration(File file) {
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(file.getAbsolutePath());
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            retriever.release();
            if (durationStr != null) {
                return Long.parseLong(durationStr) / 1000; // duration in seconds
            } else {
                return -1;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    public static boolean isValidProxy(String proxy) {
        if (proxy == null || proxy.isEmpty()) return false;

        // Regex for general format match (http/socks5, optional user:pass, host, port)
        String regex = "^(?:(socks5|http)://)?(?:(\\w+):(\\w+)@)?([\\w.-]+):(\\d+)$";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(proxy);

        if (!matcher.matches()) return false;

        String host = matcher.group(4);

        // If it looks like an IPv4 address (e.g., 192.168.0.1), we check more closely.
        if (host != null && host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            String[] parts = host.split("\\.");
            if (parts.length != 4) return false;
            for (String part : parts) {
                try {
                    int num = Integer.parseInt(part);
                    if (num < 0 || num > 255) return false;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Creates a Proxy from the user's proxy settings.
     * @return Proxy instance, or null if proxy is not enabled/configured
     */
    public static Proxy createProxy(SharedPreferences sp) {
        String proxyInput = DictatePrefsKt.get(sp, Pref.ProxyHost.INSTANCE);
        boolean proxyEnabled = DictatePrefsKt.get(sp, Pref.ProxyEnabled.INSTANCE);

        if (!proxyEnabled || proxyInput.isEmpty()) return null;

        Pattern pattern = Pattern.compile("^(?:(socks5|http)://)?(?:(\\w+):(\\w+)@)?([\\w.-]+):(\\d+)$");
        Matcher matcher = pattern.matcher(proxyInput);

        if (matcher.matches()) {
            String type = matcher.group(1);
            String host = matcher.group(4);
            int port = Integer.parseInt(matcher.group(5));

            Proxy.Type proxyType = Proxy.Type.HTTP;
            if ("socks5".equalsIgnoreCase(type)) proxyType = Proxy.Type.SOCKS;

            return new Proxy(proxyType, new InetSocketAddress(host, port));
        }
        return null;
    }

    /**
     * Creates a proxy Authenticator if credentials are configured.
     * Sets it as the default Authenticator.
     */
    public static void applyProxyAuthenticator(SharedPreferences sp) {
        String proxyInput = DictatePrefsKt.get(sp, Pref.ProxyHost.INSTANCE);
        boolean proxyEnabled = DictatePrefsKt.get(sp, Pref.ProxyEnabled.INSTANCE);

        if (!proxyEnabled || proxyInput.isEmpty()) return;

        Pattern pattern = Pattern.compile("^(?:(socks5|http)://)?(?:(\\w+):(\\w+)@)?([\\w.-]+):(\\d+)$");
        Matcher matcher = pattern.matcher(proxyInput);

        if (matcher.matches()) {
            String user = matcher.group(2);
            String pass = matcher.group(3);

            if (user != null && pass != null) {
                Authenticator.setDefault(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(user, pass.toCharArray());
                    }
                });
            }
        }
    }

    /**
     * Applies proxy settings to an OpenAI SDK client builder.
     * Uses createProxy() and applyProxyAuthenticator() internally.
     */
    public static void applyProxy(OpenAIOkHttpClient.Builder clientBuilder, SharedPreferences sp) {
        Proxy proxy = createProxy(sp);
        if (proxy != null) {
            clientBuilder.proxy(proxy);
            applyProxyAuthenticator(sp);
        }
    }

    /**
     * Applies proxy settings to an Anthropic SDK client builder.
     * Uses createProxy() and applyProxyAuthenticator() internally.
     */
    public static void applyProxyToAnthropic(AnthropicOkHttpClient.Builder clientBuilder, SharedPreferences sp) {
        Proxy proxy = createProxy(sp);
        if (proxy != null) {
            clientBuilder.proxy(proxy);
            applyProxyAuthenticator(sp);
        }
    }

    public static String translateLanguageToEmoji(String language) {
        switch (language) {
            case "detect":
                return "\u2728";
            case "af":
                return "\uD83C\uDDFF\uD83C\uDDE6";
            case "sq":
                return "\uD83C\uDDE6\uD83C\uDDF1";
            case "ar":
                return "\uD83C\uDDF8\uD83C\uDDE6";
            case "hy":
                return "\uD83C\uDDE6\uD83C\uDDF2";
            case "az":
                return "\uD83C\uDDE6\uD83C\uDDFF";
            case "eu":
                return "\uD83C\uDDEA\uD83C\uDDF8";
            case "be":
                return "\uD83C\uDDE7\uD83C\uDDFE";
            case "bn":
                return "\uD83C\uDDE7\uD83C\uDDE9";
            case "bg":
                return "\uD83C\uDDE7\uD83C\uDDEC";
            case "yue-CN":
                return "\uD83C\uDDE8\uD83C\uDDF3";
            case "yue-HK":
                return "\uD83C\uDDED\uD83C\uDDF0";
            case "ca":
                return "\uD83C\uDDE6\uD83C\uDDE9";
            case "cs":
                return "\uD83C\uDDE8\uD83C\uDDFF";
            case "da":
                return "\uD83C\uDDE9\uD83C\uDDF0";
            case "nl":
                return "\uD83C\uDDF3\uD83C\uDDF1";
            case "en":
                return "\uD83C\uDDEC\uD83C\uDDE7";
            case "et":
                return "\uD83C\uDDEA\uD83C\uDDEA";
            case "fi":
                return "\uD83C\uDDEB\uD83C\uDDEE";
            case "fr":
                return "\uD83C\uDDEB\uD83C\uDDF7";
            case "gl":
                return "\uD83C\uDDEA\uD83C\uDDF8";
            case "de":
                return "\uD83C\uDDE9\uD83C\uDDEA";
            case "el":
                return "\uD83C\uDDEC\uD83C\uDDF7";
            case "he":
                return "\uD83C\uDDEE\uD83C\uDDF1";
            case "hi":
                return "\uD83C\uDDEE\uD83C\uDDF3";
            case "hu":
                return "\uD83C\uDDED\uD83C\uDDFA";
            case "id":
                return "\uD83C\uDDEE\uD83C\uDDE9";
            case "it":
                return "\uD83C\uDDEE\uD83C\uDDF9";
            case "ja":
                return "\uD83C\uDDEF\uD83C\uDDF5";
            case "kk":
                return "\uD83C\uDDF0\uD83C\uDDFF";
            case "ko":
                return "\uD83C\uDDF0\uD83C\uDDF7";
            case "lv":
                return "\uD83C\uDDF1\uD83C\uDDFB";
            case "lt":
                return "\uD83C\uDDF1\uD83C\uDDF9";
            case "mk":
                return "\uD83C\uDDF2\uD83C\uDDF0";
            case "zh-CN":
                return "\uD83C\uDDE8\uD83C\uDDF3";
            case "zh-TW":
                return "\uD83C\uDDF9\uD83C\uDDFC";
            case "mr":
                return "\uD83C\uDDEE\uD83C\uDDF3";
            case "ne":
                return "\uD83C\uDDF3\uD83C\uDDF5";
            case "nn":
                return "\uD83C\uDDF3\uD83C\uDDF4";
            case "fa":
                return "\uD83C\uDDEE\uD83C\uDDF7";
            case "pl":
                return "\uD83C\uDDF5\uD83C\uDDF1";
            case "pt":
                return "\uD83C\uDDF5\uD83C\uDDF9";
            case "pa":
                return "\uD83C\uDDEE\uD83C\uDDF3";
            case "ro":
                return "\uD83C\uDDF7\uD83C\uDDF4";
            case "ru":
                return "\uD83C\uDDF7\uD83C\uDDFA";
            case "sr":
                return "\uD83C\uDDF7\uD83C\uDDF8";
            case "sk":
                return "\uD83C\uDDF8\uD83C\uDDF0";
            case "sl":
                return "\uD83C\uDDF8\uD83C\uDDEE";
            case "es":
                return "\uD83C\uDDEA\uD83C\uDDF8";
            case "sw":
                return "\uD83C\uDDF9\uD83C\uDDFF";
            case "sv":
                return "\uD83C\uDDF8\uD83C\uDDEA";
            case "ta":
                return "\uD83C\uDDF1\uD83C\uDDF0";
            case "th":
                return "\uD83C\uDDF9\uD83C\uDDED";
            case "tr":
                return "\uD83C\uDDF9\uD83C\uDDF7";
            case "uk":
                return "\uD83C\uDDFA\uD83C\uDDE6";
            case "ur":
                return "\uD83C\uDDF5\uD83C\uDDF0";
            case "vi":
                return "\uD83C\uDDFB\uD83C\uDDF3";
            case "cy":
                return "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC77\uDB40\uDC6C\uDB40\uDC73\uDB40\uDC7F";
            default:
                return "";
        }
    }

    public static int darkenColor(int color, float amount) {
        float factor = clamp(amount);
        int alpha = Color.alpha(color);
        int red = Math.round(Color.red(color) * (1f - factor));
        int green = Math.round(Color.green(color) * (1f - factor));
        int blue = Math.round(Color.blue(color) * (1f - factor));
        return Color.argb(alpha, red, green, blue);
    }

    private static float clamp(float value) {
        return Math.max((float) 0.0, Math.min((float) 1.0, value));
    }
}
