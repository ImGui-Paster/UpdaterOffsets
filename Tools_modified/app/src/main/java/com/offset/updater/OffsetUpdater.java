package com.offset.updater;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Obfuscate
public final class OffsetUpdater {

    // Game versions
    public static final int VERSION_GLOBAL = 1;
    public static final int VERSION_VNG    = 2;
    public static final int VERSION_BGMI   = 3;

    // Bitness
    public static final int BITS_64 = 64;
    public static final int BITS_32 = 32;

    public static final class Result {
        public final String output;
        public final List<String> log;
        public int updated = 0;
        public int same = 0;
        public int notFound = 0;
        public int standalone = 0;
        public int ambiguous = 0;
        public boolean stopped = false;

        Result(String output, List<String> log) {
            this.output = output;
            this.log = log;
        }
    }

    private static final Pattern SDK_CLASS =
            Pattern.compile("^\\s*Class\\s*:\\s*(.*?)\\s*$");
    private static final Pattern SDK_FIELD =
            Pattern.compile("^(.+?)(\\w+);//(.*?)\\[Offset:\\s*0x([0-9a-fA-F]+)");
    private static final Pattern OFFSET_CLASS =
            Pattern.compile("^\\s*//\\s*Class:\\s*(.*?)\\s*$");
    private static final Pattern OFFSET_MEMBER =
            Pattern.compile("^(\\s*)([A-Za-z_]\\w*)\\s*=\\s*(0x[0-9a-fA-F]+)(.*)$");

    // Matches: void GameType64(int a){ or void GameType32(int a){
    private static final Pattern GAMETYPE_FUNC =
            Pattern.compile("^\\s*void\\s+GameType(64|32)\\s*\\(");
    // Matches: if(a == 1){ or } else if(a == 2) {
    private static final Pattern BLOCK_START =
            Pattern.compile("(?:^\\s*if\\s*\\(\\s*a\\s*==\\s*(\\d+)\\s*\\)|^\\s*\\}\\s*else\\s+if\\s*\\(\\s*a\\s*==\\s*(\\d+)\\s*\\))");
    // Comment tag: //global or //vng or //bgmi (case-insensitive, anywhere in line)
    private static final Pattern REGION_TAG =
            Pattern.compile("//\\s*(global|vng|vietnam|bgmi)", Pattern.CASE_INSENSITIVE);

    private OffsetUpdater() {}

    private static String norm(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private static void addSpec(Map<String, Map<String, List<String>>> m, String cls, String spec, String off) {
        m.computeIfAbsent(cls, k -> new HashMap<String, List<String>>())
         .computeIfAbsent(spec, k -> new ArrayList<String>())
         .add(off);
    }

    /**
     * Detect which GameType function and version block is present in the offset file.
     * Returns int[2]: { bits (64 or 32, 0=unknown), versionIndex (1/2/3, 0=unknown) }
     */
    public static int[] detectTarget(String offsetContent) {
        int[] result = new int[]{0, 0};
        boolean inFunc = false;
        int detectedBits = 0;

        for (String line : offsetContent.split("\\r?\\n", -1)) {
            Matcher fm = GAMETYPE_FUNC.matcher(line);
            if (fm.find()) {
                inFunc = true;
                detectedBits = Integer.parseInt(fm.group(1));
                result[0] = detectedBits;
                continue;
            }
            if (!inFunc) continue;

            // Look for region tag comment: //global, //vng, //bgmi
            Matcher tm = REGION_TAG.matcher(line.toLowerCase());
            if (tm.find()) {
                String tag = tm.group(1).toLowerCase();
                if (tag.equals("global")) { result[1] = VERSION_GLOBAL; break; }
                if (tag.equals("vng") || tag.equals("vietnam")) { result[1] = VERSION_VNG; break; }
                if (tag.equals("bgmi")) { result[1] = VERSION_BGMI; break; }
            }
        }
        return result;
    }

    /**
     * Checks if the offset file contains both GameType64 and GameType32.
     */
    public static boolean hasBothBitnesses(String offsetContent) {
        boolean has64 = offsetContent.contains("GameType64");
        boolean has32 = offsetContent.contains("GameType32");
        return has64 && has32;
    }

    /**
     * Extract only the lines inside if(a == versionIndex) { ... } block
     * of the matching GameType function (64 or 32).
     * Returns the extracted block lines (without the if/else if header lines).
     */
    private static List<String> extractBlock(String[] lines, int bits, int versionIndex) {
        List<String> block = new ArrayList<>();
        boolean inFunc = false;
        boolean inBlock = false;
        int braceDepth = 0;
        int funcBraceDepth = 0;

        for (String line : lines) {
            // Find the right GameType function
            Matcher fm = GAMETYPE_FUNC.matcher(line);
            if (fm.find()) {
                int b = Integer.parseInt(fm.group(1));
                if (b == bits) {
                    inFunc = true;
                    funcBraceDepth = 0;
                }
                continue;
            }

            if (!inFunc) continue;

            // Count braces to know when the function ends
            for (char c : line.toCharArray()) {
                if (c == '{') funcBraceDepth++;
                else if (c == '}') funcBraceDepth--;
            }
            if (funcBraceDepth < 0) { inFunc = false; inBlock = false; break; }

            // Match if(a == N) or } else if(a == N)
            Matcher bm = BLOCK_START.matcher(line);
            if (bm.find()) {
                String g1 = bm.group(1), g2 = bm.group(2);
                String numStr = g1 != null ? g1 : g2;
                int blockNum = Integer.parseInt(numStr);
                if (blockNum == versionIndex) {
                    inBlock = true;
                    braceDepth = 0;
                } else {
                    inBlock = false;
                }
                continue;
            }

            if (!inBlock) continue;

            // Track brace depth inside the block (we're past the opening {)
            if (braceDepth == 0 && line.trim().equals("{")) {
                braceDepth = 1;
                continue;
            }

            int delta = 0;
            for (char c : line.toCharArray()) {
                if (c == '{') delta++;
                else if (c == '}') delta--;
            }

            if (braceDepth == 0 && delta < 0) {
                // closing brace of the if block before we even opened - block ended
                inBlock = false;
                continue;
            }

            braceDepth += delta;

            if (braceDepth < 0 || (braceDepth == 0 && line.trim().equals("}"))) {
                inBlock = false;
                continue;
            }

            block.add(line);
        }
        return block;
    }

    public static Result update(String sdkContent, String offsetContent, int bits, int versionIndex) {
        Thread.interrupted();
        List<String> log = new ArrayList<>();

        // --- Parse SDK ---
        Map<String, Map<String, List<String>>> classSpecs = new HashMap<>();
        String curClass = null;
        int sdkFields = 0, sdkLines = 0;
        String firstNonEmpty = "";

        if (sdkContent.startsWith("\uFEFF")) sdkContent = sdkContent.substring(1);

        for (String line : sdkContent.split("\\r?\\n", -1)) {
            if (Thread.currentThread().isInterrupted()) break;
            sdkLines++;
            if (firstNonEmpty.isEmpty() && !line.trim().isEmpty()) firstNonEmpty = line.trim();

            Matcher m = SDK_CLASS.matcher(line);
            if (m.matches()) { curClass = m.group(1); continue; }
            if (curClass == null) continue;

            m = SDK_FIELD.matcher(line.trim());
            if (m.find()) {
                String type = norm(m.group(1));
                String name = m.group(2);
                String off = "0x" + m.group(4).toLowerCase();
                addSpec(classSpecs, curClass, type + " " + name + ";//", off);
                sdkFields++;
            }
        }

        if (Thread.currentThread().isInterrupted()) {
            log.add("Stopped. SDK parse cancelled; file NOT modified.");
            Result stopped = new Result("", log);
            stopped.stopped = true;
            return stopped;
        }

        String bitsLabel = bits + "-bit";
        String verLabel = versionIndex == VERSION_GLOBAL ? "Global" : versionIndex == VERSION_VNG ? "VNG" : "BGMI";
        log.add("Target: " + bitsLabel + " / " + verLabel);
        log.add("SDK parsed: " + classSpecs.size() + " classes, " + sdkFields + " fields.");

        if (classSpecs.isEmpty()) {
            log.add("WARNING: no 'Class:' sections found in SDK (" + sdkLines + " lines, first: '" + firstNonEmpty + "').");
            log.add("The selected SDK file may be empty or wrong format. No offsets will be updated.");
        }

        // --- Find the target block in Offsets.h ---
        String[] allLines = offsetContent.split("\\r?\\n", -1);
        List<String> blockLines = extractBlock(allLines, bits, versionIndex);

        if (blockLines.isEmpty()) {
            log.add("WARNING: Could not find GameType" + bits + "(a==" + versionIndex + ") block in Offsets.h!");
            log.add("Make sure your Offsets.h has GameType" + bits + "() with if(a == " + versionIndex + ") { ... }");
            Result r = new Result(offsetContent, log);
            return r;
        }
        log.add("Found block: " + blockLines.size() + " lines to process.");

        // --- Process lines inside the block ---
        Result res = new Result("", log);
        // Build a map: lineIndex-in-allLines -> updated line
        // First, find where the block lines start in allLines
        // We do a second pass - rewrite only lines inside the target block, keep everything else
        StringBuilder out = new StringBuilder();
        boolean inFunc = false;
        boolean inTargetBlock = false;
        boolean inOtherBlock = false;
        int funcBraceDepth2 = 0;
        int blockBraceDepth2 = 0;
        curClass = null;
        int funcBits = 0;

        for (String line : allLines) {
            if (Thread.currentThread().isInterrupted()) break;

            // Detect GameType function
            Matcher fm = GAMETYPE_FUNC.matcher(line);
            if (fm.find()) {
                funcBits = Integer.parseInt(fm.group(1));
                inFunc = true;
                funcBraceDepth2 = 0;
                inTargetBlock = false;
                inOtherBlock = false;
                out.append(line).append('\n');
                continue;
            }

            if (inFunc) {
                for (char c : line.toCharArray()) {
                    if (c == '{') funcBraceDepth2++;
                    else if (c == '}') funcBraceDepth2--;
                }
                if (funcBraceDepth2 < 0) {
                    inFunc = false;
                    inTargetBlock = false;
                    inOtherBlock = false;
                    curClass = null;
                    out.append(line).append('\n');
                    continue;
                }

                // Detect block start
                Matcher bm = BLOCK_START.matcher(line);
                if (bm.find()) {
                    String g1 = bm.group(1), g2 = bm.group(2);
                    String numStr = g1 != null ? g1 : g2;
                    int blockNum = Integer.parseInt(numStr);
                    boolean isTarget = (funcBits == bits && blockNum == versionIndex);
                    inTargetBlock = isTarget;
                    inOtherBlock = !isTarget;
                    blockBraceDepth2 = 0;
                    curClass = null;
                    out.append(line).append('\n');
                    continue;
                }
            }

            // Track class comments inside target block
            if (inTargetBlock) {
                Matcher cm = OFFSET_CLASS.matcher(line);
                if (cm.matches()) {
                    curClass = cm.group(1);
                    out.append(line).append('\n');
                    continue;
                }

                Matcher mm = OFFSET_MEMBER.matcher(line);
                if (mm.matches()) {
                    String name = mm.group(2);
                    String oldHex = mm.group(3);
                    String rest = mm.group(4);

                    int ci = rest.indexOf("//");
                    String comment = ci >= 0 ? rest.substring(ci).trim() : null;

                    if (curClass == null) {
                        res.standalone++;
                        out.append(line).append('\n');
                        log.add("kept (no class): " + name + " = " + oldHex);
                        continue;
                    }

                    String spec = null;
                    String fieldName = name;
                    if (comment != null) {
                        String raw = norm(comment.substring(2));
                        int j = raw.indexOf(";//");
                        if (j >= 0) {
                            String typeAndName = norm(raw.substring(0, j));
                            spec = typeAndName + ";//";
                            fieldName = typeAndName.isEmpty()
                                    ? name : typeAndName.substring(typeAndName.lastIndexOf(' ') + 1);
                        }
                    }

                    String newVal = null;
                    if (spec != null && classSpecs.get(curClass) != null && classSpecs.get(curClass).get(spec) != null) {
                        List<String> hits = classSpecs.get(curClass).get(spec);
                        if (hits.size() > 1) {
                            res.ambiguous++;
                            out.append(line).append('\n');
                            log.add("AMBIGUOUS: " + curClass + " -> " + spec + " has " + hits.size() + " matches " + hits);
                            continue;
                        }
                        newVal = hits.get(0);
                    }

                    if (newVal == null) {
                        res.notFound++;
                        out.append(line).append('\n');
                        log.add("NOT FOUND: " + curClass + " -> " + fieldName + "  (kept " + name + " = " + oldHex + ")");
                        continue;
                    }

                    if (newVal.equals(oldHex)) {
                        res.same++;
                        out.append(line).append('\n');
                        continue;
                    }

                    res.updated++;
                    int eq = line.indexOf('=');
                    int pos = eq >= 0 ? line.indexOf(oldHex, eq) : -1;
                    if (pos >= 0) {
                        out.append(line.substring(0, pos)).append(newVal)
                           .append(line.substring(pos + oldHex.length())).append('\n');
                    } else {
                        out.append(line).append('\n');
                    }
                    log.add("updated: " + curClass + " -> " + fieldName + " -> " + oldHex + " -> " + newVal);
                    continue;
                }
            }

            // All other lines (outside target block, or outside function) — copy as-is
            out.append(line).append('\n');
        }

        if (Thread.currentThread().isInterrupted()) {
            log.add("Stopped. Update cancelled; file NOT modified.");
            Result stopped = new Result("", log);
            stopped.stopped = true;
            return stopped;
        }

        log.add("Finished. Updated " + res.updated + " / same " + res.same
                + " / not-found " + res.notFound
                + " / ambiguous " + res.ambiguous + " / no-class " + res.standalone + ".");
        Result result = new Result(out.toString(), log);
        result.updated = res.updated;
        result.same = res.same;
        result.notFound = res.notFound;
        result.standalone = res.standalone;
        result.ambiguous = res.ambiguous;
        return result;
    }
}
