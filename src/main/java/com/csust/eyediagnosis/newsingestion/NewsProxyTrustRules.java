package com.csust.eyediagnosis.newsingestion;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 「Tomcat 实际信任的代理范围」与「运维声明的可信代理范围」之间的核对。
 *
 * <p>Tomcat 的 {@code server.tomcat.remoteip.internal-proxies} 是一个正则，命中即认为该对端
 * 可以代表客户端说话（{@code X-Forwarded-Proto} 决定 {@code isSecure()}，
 * {@code X-Forwarded-For} 决定 {@code getRemoteAddr()}）。它最常见的危险写法是抄默认值——
 * 信任整个 10/8、172.16/12、192.168/16 与 127/8：那样局域网内任意主机都能伪造 HTTPS 状态。
 *
 * <p>应用侧无法枚举一个正则到底覆盖了哪些地址，但可以核对两件事：
 * 声明的每个代理都必须在正则里（否则声明与运行时不一致），以及正则不能命中声明范围之外的
 * 邻近地址（否则实际信任范围比声明宽）。这里用「声明范围的边界外探针 + 若干哨兵地址」来判定。
 */
public final class NewsProxyTrustRules {

    /**
     * 探针地址：只要正则命中其中任何一个，就说明信任范围比声明宽（这些地址位于常见私网段的
     * 边缘或明确的非私网段，正常部署不会把反代配在这些地址上）。
     */
    private static final List<String> SENTINEL_ADDRESSES = List.of(
            "10.255.255.254",
            "10.0.0.1",
            "172.31.255.254",
            "172.16.0.1",
            "192.168.255.254",
            "192.168.0.1",
            "169.254.0.1",
            "127.0.0.2",
            "203.0.113.1",
            "198.51.100.1");

    private NewsProxyTrustRules() {
    }

    /**
     * @param kind    问题分类
     * @param detail  给人看的说明（不含攻击者可控内容）
     */
    public record Finding(String kind, String detail) {
    }

    /**
     * @param internalProxies Tomcat 的 internal-proxies 正则（可为空）
     * @param declared        运维声明的可信代理地址（精确 IP 或 CIDR）
     */
    public static List<Finding> check(String internalProxies, List<String> declared) {
        List<Finding> findings = new ArrayList<>();
        if (internalProxies == null || internalProxies.isBlank()) {
            findings.add(new Finding("PATTERN_MISSING",
                    "server.tomcat.remoteip.internal-proxies 未配置，无法确认可信代理边界"));
            return findings;
        }
        List<String> cleaned = new ArrayList<>();
        if (declared != null) {
            for (String entry : declared) {
                if (entry != null && !entry.isBlank()) {
                    cleaned.add(entry.trim());
                }
            }
        }
        if (cleaned.isEmpty()) {
            findings.add(new Finding("DECLARATION_MISSING",
                    "eye-diagnosis.news-ingestion.trusted-proxy-addresses 未声明，"
                            + "无法确认哪些反向代理有权代表客户端"));
            return findings;
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(internalProxies, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException ex) {
            findings.add(new Finding("PATTERN_INVALID",
                    "server.tomcat.remoteip.internal-proxies 不是合法正则"));
            return findings;
        }

        List<long[]> ranges = new ArrayList<>();
        for (String entry : cleaned) {
            long[] range = parseRange(entry);
            if (range == null) {
                if (!pattern.matcher(entry).matches()) {
                    findings.add(new Finding("DECLARATION_NOT_TRUSTED",
                            "声明的可信代理 " + entry + " 不在 server.tomcat.remoteip.internal-proxies 的信任范围内"));
                }
                continue;
            }
            ranges.add(range);
            String start = format(range[0]);
            String end = format(range[1]);
            if (!pattern.matcher(start).matches() || !pattern.matcher(end).matches()) {
                findings.add(new Finding("DECLARATION_NOT_TRUSTED",
                        "声明的可信代理 " + entry + "（含边界 " + start + " / " + end
                                + "）不在 server.tomcat.remoteip.internal-proxies 的信任范围内"));
            }
        }

        Set<String> probes = new LinkedHashSet<>(SENTINEL_ADDRESSES);
        for (long[] range : ranges) {
            probes.add(format(range[0] - 1));
            probes.add(format(range[1] + 1));
        }
        for (String probe : probes) {
            long address = parseIpv4(probe);
            if (address < 0 || insideDeclared(address, ranges)) {
                continue;
            }
            if (pattern.matcher(probe).matches()) {
                findings.add(new Finding("TRUST_BROADER_THAN_DECLARED",
                        "server.tomcat.remoteip.internal-proxies 命中了声明范围之外的地址 " + probe
                                + "（信任范围比声明宽，同网段主机可能伪造 X-Forwarded-Proto）"));
            }
        }
        return findings;
    }

    private static boolean insideDeclared(long address, List<long[]> ranges) {
        for (long[] range : ranges) {
            if (address >= range[0] && address <= range[1]) {
                return true;
            }
        }
        return false;
    }

    /** 精确 IP 返回单点区间，{@code a.b.c.d/nn} 返回该网段区间，其余（IPv6 等）返回 null。 */
    private static long[] parseRange(String entry) {
        int slash = entry.indexOf('/');
        if (slash < 0) {
            long address = parseIpv4(entry);
            return address < 0 ? null : new long[]{address, address};
        }
        String addressPart = entry.substring(0, slash);
        long address = parseIpv4(addressPart);
        if (address < 0) {
            return null;
        }
        int prefix;
        try {
            prefix = Integer.parseInt(entry.substring(slash + 1).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
        if (prefix < 0 || prefix > 32) {
            return null;
        }
        long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        long start = address & mask;
        long end = start | (~mask & 0xFFFFFFFFL);
        return new long[]{start, end};
    }

    private static long parseIpv4(String value) {
        if (value == null) {
            return -1;
        }
        String[] parts = value.trim().split("\\.");
        if (parts.length != 4) {
            return -1;
        }
        long result = 0;
        for (String part : parts) {
            int octet;
            try {
                octet = Integer.parseInt(part);
            } catch (NumberFormatException ex) {
                return -1;
            }
            if (octet < 0 || octet > 255) {
                return -1;
            }
            result = (result << 8) | octet;
        }
        return result;
    }

    private static String format(long address) {
        if (address < 0 || address > 0xFFFFFFFFL) {
            return "0.0.0.0";
        }
        return ((address >> 24) & 0xFF) + "." + ((address >> 16) & 0xFF) + "."
                + ((address >> 8) & 0xFF) + "." + (address & 0xFF);
    }
}
