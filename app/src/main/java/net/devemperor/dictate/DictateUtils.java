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
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.devemperor.dictate.ai.prompt.PromptTemplates;
import net.devemperor.dictate.preferences.DictatePrefsKt;
import net.devemperor.dictate.preferences.Pref;

public class DictateUtils {

    public static final String PROMPT_PUNCTUATION_CAPITALIZATION = "Hello, how are you? I'm doing well! Yes, it starts at 3:00 p.m.";
    /** @deprecated Use {@link PromptTemplates#SYSTEM_PROMPT_BE_PRECISE} instead. */
    @Deprecated
    public static final String PROMPT_REWORDING_BE_PRECISE = PromptTemplates.SYSTEM_PROMPT_BE_PRECISE;
    private static final Map<String, String> PROMPT_PUNCTUATION_CAPITALIZATION_BY_LANGUAGE;

    static {
        Map<String, String> prompts = new HashMap<>();
        prompts.put("af", "Hallo, hoe gaan dit? Dit gaan goed! Ja, dit begin om 15:00.");
        prompts.put("sq", "Përshëndetje, si jeni? Jam mirë! Po, fillon në orën 15:00.");
        prompts.put("ar", "\u0645\u0631\u062d\u0628\u064b\u0627\u060c \u0643\u064a\u0641 \u062d\u0627\u0644\u0643\u061f \u0623\u0646\u0627 \u0628\u062e\u064a\u0631! \u0646\u0639\u0645\u060c \u064a\u0628\u062f\u0623 \u0627\u0644\u0633\u0627\u0639\u0629 3:00 \u0645\u0633\u0627\u0621\u064b.");
        prompts.put("hy", "\u0532\u0561\u0580\u0587, \u056b\u0576\u0579\u057a\u0565\u055e\u057d \u0565\u057d? \u0535\u057d \u056c\u0561\u057e \u0565\u0574! \u0531\u0575\u0578, \u057d\u056f\u057d\u057e\u0578\u0582\u0574 \u0567 \u056a\u0561\u0574\u0568 3:00-\u056b\u0576:");
        prompts.put("az", "Salam, necəsiniz? Yaxşıyam! Bəli, saat 15:00-da başlayır.");
        prompts.put("eu", "Kaixo, zer moduz? Ondo nago! Bai, 15:00etan hasten da.");
        prompts.put("be", "\u0412\u0456\u0442\u0430\u044e, \u044f\u043a \u0441\u043f\u0440\u0430\u0432\u044b? \u0423 \u043c\u044f\u043d\u0435 \u045e\u0441\u0451 \u0434\u043e\u0431\u0440\u0430! \u0422\u0430\u043a, \u043f\u0430\u0447\u044b\u043d\u0430\u0435\u0446\u0446\u0430 \u045e 15:00.");
        prompts.put("bn", "\u09b9\u09cd\u09af\u09be\u09b2\u09cb, \u0995\u09c7\u09ae\u09a8 \u0986\u099b\u09c7\u09a8? \u0986\u09ae\u09bf \u09ad\u09be\u09b2\u09cb \u0986\u099b\u09bf! \u09b9\u09cd\u09af\u09be\u0981, \u098f\u099f\u09be \u09ac\u09bf\u0995\u09be\u09b2 \u09e9:00-\u09a4\u09c7 \u09b6\u09c1\u09b0\u09c1 \u09b9\u09af\u09bc\u0964");
        prompts.put("bg", "\u0417\u0434\u0440\u0430\u0432\u0435\u0439\u0442\u0435, \u043a\u0430\u043a \u0441\u0442\u0435? \u0410\u0437 \u0441\u044a\u043c \u0434\u043e\u0431\u0440\u0435! \u0414\u0430, \u0437\u0430\u043f\u043e\u0447\u0432\u0430 \u0432 15:00 \u0447.");
        prompts.put("yue-cn", "\u4f60\u597d\uff0c\u4f60\u70b9\u5440\uff1f\u6211\u51e0\u597d\uff01\u4fc2\u5440\uff0c\u4e0b\u663d3\u70b9\u5f00\u59cb\u3002");
        prompts.put("yue-hk", "\u4f60\u597d\uff0c\u4f60\u9ede\u5440\uff1f\u6211\u5e7e\u597d\uff01\u4fc2\u5440\uff0c\u4e0b\u665d3\u9ede\u958b\u59cb\u3002");
        prompts.put("ca", "Hola, com estàs? Estic bé! Sí, comença a les 15:00.");
        prompts.put("cs", "Ahoj, jak se máš? Mám se dobře! Ano, začíná ve 15:00.");
        prompts.put("da", "Hej, hvordan har du det? Jeg har det godt! Ja, det starter kl. 15:00.");
        prompts.put("nl", "Hallo, hoe gaat het? Het gaat goed! Ja, het begint om 15:00 uur.");
        prompts.put("en", PROMPT_PUNCTUATION_CAPITALIZATION);
        prompts.put("et", "Tere, kuidas läheb? Mul läheb hästi! Jah, see algab kell 15:00.");
        prompts.put("fi", "Hei, mitä kuuluu? Minulla menee hyvin! Kyllä, se alkaa klo 15:00.");
        prompts.put("fr", "Bonjour, comment allez-vous ? Je vais bien ! Oui, ça commence à 15 h 00.");
        prompts.put("gl", "Ola, como estás? Estou ben! Si, comeza ás 15:00.");
        prompts.put("de", "Hallo, wie geht es dir? Mir geht es gut! Ja, es beginnt um 15:00 Uhr.");
        prompts.put("el", "\u0393\u03b5\u03b9\u03b1, \u03c0\u03ce\u03c2 \u03b5\u03af\u03c3\u03b1\u03b9; \u0395\u03af\u03bc\u03b1\u03b9 \u03ba\u03b1\u03bb\u03ac! \u039d\u03b1\u03b9, \u03be\u03b5\u03ba\u03b9\u03bd\u03ac \u03c3\u03c4\u03b9\u03c2 3:00 \u03bc.\u03bc.");
        prompts.put("he", "\u05e9\u05dc\u05d5\u05dd, \u05de\u05d4 \u05e9\u05dc\u05d5\u05de\u05da? \u05d0\u05e0\u05d9 \u05d1\u05e1\u05d3\u05e8! \u05db\u05df, \u05d6\u05d4 \u05de\u05ea\u05d7\u05d9\u05dc \u05d1\u05e9\u05e2\u05d4 15:00.");
        prompts.put("hi", "\u0928\u092e\u0938\u094d\u0924\u0947, \u0906\u092a \u0915\u0948\u0938\u0947 \u0939\u0948\u0902? \u092e\u0948\u0902 \u0920\u0940\u0915 \u0939\u0942\u0901! \u0939\u093e\u0901, \u092f\u0939 \u0926\u094b\u092a\u0939\u0930 3:00 \u092c\u091c\u0947 \u0936\u0941\u0930\u0942 \u0939\u094b\u0924\u093e \u0939\u0948\u0964");
        prompts.put("hu", "Szia, hogy vagy? Jól vagyok! Igen, délután 3:00-kor kezdődik.");
        prompts.put("id", "Halo, apa kabar? Saya baik-baik saja! Ya, dimulai pukul 15:00.");
        prompts.put("it", "Ciao, come stai? Sto bene! Sì, inizia alle 15:00.");
        prompts.put("ja", "\u3053\u3093\u306b\u3061\u306f\u3001\u304a\u5143\u6c17\u3067\u3059\u304b\uff1f\u5143\u6c17\u3067\u3059\uff01\u306f\u3044\u3001\u5348\u5f8c3\u6642\u306b\u59cb\u307e\u308a\u307e\u3059\u3002");
        prompts.put("kk", "\u0421\u04d9\u043b\u0435\u043c, \u049b\u0430\u043b\u044b\u04a3\u044b\u0437 \u049b\u0430\u043b\u0430\u0439? \u041c\u0435\u043d \u0436\u0430\u049b\u0441\u044b\u043c\u044b\u043d! \u0418\u04d9, \u0441\u0430\u0493\u0430\u0442 15:00-\u0434\u0435 \u0431\u0430\u0441\u0442\u0430\u043b\u0430\u0434\u044b.");
        prompts.put("ko", "\uc548\ub155\ud558\uc138\uc694, \uc5b4\ub5bb\uac8c \uc9c0\ub0b4\uc138\uc694? \uc798 \uc9c0\ub0b4\uace0 \uc788\uc5b4\uc694! \ub124, \uc624\ud6c4 3\uc2dc\uc5d0 \uc2dc\uc791\ud569\ub2c8\ub2e4.");
        prompts.put("lv", "Sveiki, kā jums klājas? Man iet labi! Jā, tas sākas pulksten 15:00.");
        prompts.put("lt", "Sveiki, kaip sekasi? Man viskas gerai! Taip, prasideda 15:00 val.");
        prompts.put("mk", "\u0417\u0434\u0440\u0430\u0432\u043e, \u043a\u0430\u043a\u043e \u0441\u0442\u0435? \u0414\u043e\u0431\u0440\u043e \u0441\u0443\u043c! \u0414\u0430, \u043f\u043e\u0447\u043d\u0443\u0432\u0430 \u0432\u043e 15:00 \u0447.");
        prompts.put("zh-cn", "\u4f60\u597d\uff0c\u4f60\u597d\u5417\uff1f\u6211\u5f88\u597d\uff01\u662f\u7684\uff0c\u4e0b\u5348 3:00 \u5f00\u59cb\u3002");
        prompts.put("zh-tw", "\u4f60\u597d\uff0c\u4f60\u597d\u55ce\uff1f\u6211\u5f88\u597d\uff01\u662f\u7684\uff0c\u4e0b\u5348 3:00 \u958b\u59cb\u3002");
        prompts.put("mr", "\u0928\u092e\u0938\u094d\u0915\u093e\u0930, \u0924\u0941\u092e\u094d\u0939\u0940 \u0915\u0938\u0947 \u0906\u0939\u093e\u0924? \u092e\u0940 \u0920\u0940\u0915 \u0906\u0939\u0947! \u0939\u094b, \u0924\u0947 \u0926\u0941\u092a\u093e\u0930\u0940 3:00 \u0935\u093e\u091c\u0924\u093e \u0938\u0941\u0930\u0942 \u0939\u094b\u0924\u0947.");
        prompts.put("ne", "\u0928\u092e\u0938\u094d\u0924\u0947, \u0924\u092a\u093e\u0908\u0902\u0932\u093e\u0908 \u0915\u0938\u094d\u0924\u094b \u091b? \u092e\u0932\u093e\u0908 \u0938\u091e\u094d\u091a\u0948 \u091b! \u0939\u094b, \u092f\u094b \u0926\u093f\u0909\u0901\u0938\u094b 3:00 \u092c\u091c\u0947 \u0938\u0941\u0930\u0941 \u0939\u0941\u0928\u094d\u091b\u0964");
        prompts.put("nn", "Hei, korleis går det? Det går bra! Ja, det startar klokka 15:00.");
        prompts.put("fa", "\u0633\u0644\u0627\u0645\u060c \u062d\u0627\u0644\u062a \u0686\u0637\u0648\u0631\u0647\u061f \u0645\u0646 \u062e\u0648\u0628\u0645! \u0628\u0644\u0647\u060c \u0633\u0627\u0639\u062a 3:00 \u0628\u0639\u062f\u0627\u0632\u0638\u0647\u0631 \u0634\u0631\u0648\u0639 \u0645\u06cc\u200c\u0634\u0647.");
        prompts.put("pl", "Cześć, jak się masz? U mnie dobrze! Tak, zaczyna się o 15:00.");
        prompts.put("pt", "Olá, como vai? Estou bem! Sim, começa às 15:00.");
        prompts.put("pa", "\u0a38\u0a24 \u0a38\u0a4d\u0a30\u0a40 \u0a05\u0a15\u0a3e\u0a32, \u0a24\u0a41\u0a38\u0a40\u0a02 \u0a15\u0a3f\u0a35\u0a47\u0a02 \u0a39\u0a4b? \u0a2e\u0a48\u0a02 \u0a20\u0a40\u0a15 \u0a39\u0a3e\u0a02! \u0a39\u0a3e\u0a02, \u0a07\u0a39 \u0a26\u0a41\u0a2a\u0a39\u0a3f\u0a30 3:00 \u0a35\u0a1c\u0a47 \u0a36\u0a41\u0a30\u0a42 \u0a39\u0a41\u0a70\u0a26\u0a3e \u0a39\u0a48\u0964");
        prompts.put("ro", "Bună, ce mai faci? Sunt bine! Da, începe la ora 15:00.");
        prompts.put("ru", "\u041f\u0440\u0438\u0432\u0435\u0442, \u043a\u0430\u043a \u0434\u0435\u043b\u0430? \u0423 \u043c\u0435\u043d\u044f \u0432\u0441\u0451 \u0445\u043e\u0440\u043e\u0448\u043e! \u0414\u0430, \u043d\u0430\u0447\u0438\u043d\u0430\u0435\u0442\u0441\u044f \u0432 15:00.");
        prompts.put("sr", "\u0417\u0434\u0440\u0430\u0432\u043e, \u043a\u0430\u043a\u043e \u0441\u0442\u0435? \u0414\u043e\u0431\u0440\u043e \u0441\u0430\u043c! \u0414\u0430, \u043f\u043e\u0447\u0438\u045a\u0435 \u0443 15:00.");
        prompts.put("sk", "Ahoj, ako sa máš? Mám sa dobre! Áno, začína o 15:00.");
        prompts.put("sl", "Živijo, kako si? Imam se dobro! Ja, začne se ob 15:00.");
        prompts.put("es", "Hola, ¿cómo estás? ¡Estoy bien! Sí, empieza a las 3:00 p. m.");
        prompts.put("sw", "Habari, hujambo? Mimi ni mzima! Ndiyo, inaanza saa 9:00 mchana.");
        prompts.put("sv", "Hej, hur mår du? Jag mår bra! Ja, det börjar klockan 15:00.");
        prompts.put("ta", "\u0bb5\u0ba3\u0b95\u0bcd\u0b95\u0bae\u0bcd, \u0ba8\u0bc0\u0b99\u0bcd\u0b95\u0bb3\u0bcd \u0b8e\u0baa\u0bcd\u0baa\u0b9f\u0bbf \u0b87\u0bb0\u0bc1\u0b95\u0bcd\u0b95\u0bbf\u0bb1\u0bc0\u0bb0\u0bcd\u0b95\u0bb3\u0bcd? \u0ba8\u0bbe\u0ba9\u0bcd \u0ba8\u0bb2\u0bae\u0bbe\u0b95 \u0b87\u0bb0\u0bc1\u0b95\u0bcd\u0b95\u0bbf\u0bb1\u0bc7\u0ba9\u0bcd! \u0b86\u0bae\u0bcd, \u0b85\u0ba4\u0bc1 \u0bae\u0bbe\u0bb2\u0bc8 3:00 \u0bae\u0ba3\u0bbf\u0b95\u0bcd\u0b95\u0bc1 \u0ba4\u0bca\u0b9f\u0b99\u0bcd\u0b95\u0bc1\u0bae\u0bcd.");
        prompts.put("th", "\u0e2a\u0e27\u0e31\u0e2a\u0e14\u0e35\u0e04\u0e23\u0e31\u0e1a \u0e2a\u0e1a\u0e32\u0e22\u0e14\u0e35\u0e44\u0e2b\u0e21? \u0e2a\u0e1a\u0e32\u0e22\u0e14\u0e35\u0e04\u0e23\u0e31\u0e1a! \u0e43\u0e0a\u0e48\u0e04\u0e23\u0e31\u0e1a \u0e40\u0e23\u0e34\u0e48\u0e21\u0e15\u0e2d\u0e19\u0e1a\u0e48\u0e32\u0e22 3 \u0e42\u0e21\u0e07\u0e04\u0e23\u0e31\u0e1a");
        prompts.put("tr", "Merhaba, nasılsınız? İyiyim! Evet, saat 15:00'te başlıyor.");
        prompts.put("uk", "\u041f\u0440\u0438\u0432\u0456\u0442, \u044f\u043a \u0441\u043f\u0440\u0430\u0432\u0438? \u0423 \u043c\u0435\u043d\u0435 \u0432\u0441\u0435 \u0434\u043e\u0431\u0440\u0435! \u0422\u0430\u043a, \u043f\u043e\u0447\u0438\u043d\u0430\u0454\u0442\u044c\u0441\u044f \u043e 15:00.");
        prompts.put("ur", "\u0627\u0633\u0644\u0627\u0645 \u0639\u0644\u06cc\u06a9\u0645\u060c \u06a9\u06cc\u0627 \u062d\u0627\u0644 \u06c1\u06d2\u061f \u0645\u06cc\u06ba \u0679\u06be\u06cc\u06a9 \u06c1\u0648\u06ba! \u062c\u06cc \u06c1\u0627\u06ba\u060c \u06cc\u06c1 \u062f\u0648\u067e\u06c1\u0631 3:00 \u0628\u062c\u06d2 \u0634\u0631\u0648\u0639 \u06c1\u0648\u062a\u0627 \u06c1\u06d2\u06d4");
        prompts.put("vi", "Xin chào, bạn khỏe không? Tôi khỏe! Vâng, nó bắt đầu lúc 3:00 chiều.");
        prompts.put("cy", "Helo, sut wyt ti? Dwi'n dda! Ie, mae'n dechrau am 3:00 y prynhawn.");
        PROMPT_PUNCTUATION_CAPITALIZATION_BY_LANGUAGE = Collections.unmodifiableMap(prompts);
    }

    public static String getPunctuationPromptForLanguage(String languageCode) {
        if (languageCode == null || languageCode.isEmpty() || languageCode.equals("detect")) {
            return PROMPT_PUNCTUATION_CAPITALIZATION;
        }
        String normalized = languageCode.toLowerCase(Locale.ROOT);
        String prompt = PROMPT_PUNCTUATION_CAPITALIZATION_BY_LANGUAGE.get(normalized);
        if (prompt != null) return prompt;

        int separatorIndex = normalized.indexOf('-');
        if (separatorIndex > 0) {
            String baseLanguage = normalized.substring(0, separatorIndex);
            prompt = PROMPT_PUNCTUATION_CAPITALIZATION_BY_LANGUAGE.get(baseLanguage);
            if (prompt != null) return prompt;
        }

        return PROMPT_PUNCTUATION_CAPITALIZATION;
    }

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
