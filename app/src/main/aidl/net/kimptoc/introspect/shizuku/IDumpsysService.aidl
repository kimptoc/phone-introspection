package net.kimptoc.introspect.shizuku;

interface IDumpsysService {

    void destroy() = 16777114; // Reserved: Shizuku server calls this to tear the service down.

    String dumpsys(String service, int timeoutMs, int maxChars) = 1;
}
