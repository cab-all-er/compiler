import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
    private static long startTime;

    public static String pluginName = null;
    private static String pluginVersion = null;
    private static String pluginPackage = null;

    private static boolean usesData = false;
    private static boolean usesDescription = false;

    private static boolean usesFetch = false;
    private static boolean usesJsonParse = false;

    private static String dataFileName = "data.json";

    public static StringBuilder pluginCommands = new StringBuilder();
    private static Set<String> processedCommands = new HashSet<>();

    private static Set<String> knownListVars = new HashSet<>();

    private static final Set<String> knownJsonObjects = new HashSet<>();

    private static final Pattern DESCRIPTION_PATTERN = Pattern.compile(
            "description\\(\\\"([^\\\"]+)\\\"\\);?"
    );

    private static final Pattern JAVA_BLOCK_PATTERN = Pattern.compile("\\$\\^(.*?)\\^\\$", Pattern.DOTALL);

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Uso: java -jar compilador.jar <archivo.js>");
            return;
        }
        String inputFile = args[0];
        try {
            startTime = System.currentTimeMillis();

            String jsCode = Files.readString(Path.of(inputFile));

            processPluginInfo(jsCode);

            processCommands(jsCode);

            jsCode = translateJavaScriptToJava(jsCode);

            updateProgress("Convirtiendo clases.");
            generatePluginYml();

            updateProgress("Convirtiendo clases..");
            generateCommandClasses();

            updateProgress("Convirtiendo clases...");
            generateMainClass(jsCode);

            updateProgress("Compilando clases.");
            compileJavaFiles();

            updateProgress("Compilando clases..");

            deleteUnwantedJavaFiles();

            updateProgress("Generando .jar");
            createJar(pluginName + ".jar");

            showElapsedTime();

        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    private static void processPluginInfo(String jsCode) {
        Pattern dataUsagePattern = Pattern.compile("data\\.(set|get)\\(");
        Matcher dataUsageMatcher = dataUsagePattern.matcher(jsCode);
        usesData = dataUsageMatcher.find();

        if (usesData) {
            Pattern dataPattern = Pattern.compile("data\\.set\\(\\\"nombre\\\",\\s*\\\"([^\"]+)\\\"\\);");
            Matcher dataMatcher = dataPattern.matcher(jsCode);
            if (dataMatcher.find()) {
                dataFileName = dataMatcher.group(1);
            }
        }

        Pattern pattern = Pattern.compile(
                "plugin\\(\\(\\)\\s*=>\\s*\\{\\s*name\\(\\\"([^\"]+)\\\"\\);" +
                        "\\s*version\\(\\\"([^\"]+)\\\"\\);" +
                        "\\s*package\\(\\\"([^\"]+)\\\"\\);"
        );
        Matcher matcher = pattern.matcher(jsCode);

        if (matcher.find()) {
            pluginName    = matcher.group(1);
            pluginVersion = matcher.group(2);
            pluginPackage = matcher.group(3);
        } else {
            pluginName    = "MyPlugin";
            pluginVersion = "1.0";
            pluginPackage = "me.example.myplugin";
        }
    }

    private static void processCommands(String jsCode) {
        Pattern pattern = Pattern.compile(
                "command\\(\\s*\"([^\"]+)\"\\s*,\\s*\\(\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\)\\s*=>\\s*\\{(.*?)\\}\\s*\\)",
                Pattern.DOTALL
        );
        Matcher matcher = pattern.matcher(jsCode);

        if (!matcher.find()) {
            System.out.println("Contenido del archivo JS:\n" + jsCode);
            throw new RuntimeException("No se encontraron comandos en el archivo JS.");
        }

        do {
            String commandName = matcher.group(1);
            String senderParam = matcher.group(2);
            String commandBody = matcher.group(3);

            Matcher descM = DESCRIPTION_PATTERN.matcher(commandBody);
            String commandDescription = "A test command.";
            if (descM.find()) {
                commandDescription = descM.group(1);
                commandBody = descM.replaceFirst("");
            }

            generateJavaCommand(commandName, commandDescription, commandBody);

        } while (matcher.find());
    }

    public static void generateJavaCommand(String commandName, String commandDescription, String commandBody) {
        String javaCode = """
            package %s;

            import org.bukkit.command.Command;
            import org.bukkit.command.CommandExecutor;
            import org.bukkit.command.CommandSender;

            public class %s implements CommandExecutor {
                @Override
                public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
                    %s
                    return true;
                }
            }
            """.formatted(pluginPackage, capitalize(commandName), commandBody.trim());

        Path javaFile = Paths.get("output", pluginPackage.replace(".", "/"), capitalize(commandName) + ".java");
        try {
            Files.createDirectories(javaFile.getParent());
            Files.writeString(javaFile, javaCode, StandardOpenOption.CREATE);
        } catch (IOException e) {
            System.err.println("Error guardando la clase Java: " + e.getMessage());
        }

        String commandDescriptionYaml = """
            %s:
                description: %s
                usage: /%s
            """.formatted(commandName, commandDescription, commandName);

        pluginCommands.append(commandDescriptionYaml).append("\n");
    }

    private static String translateJavaScriptToJava(String jsCode) {

        if (jsCode.contains("fetch(")) {
            usesFetch = true;
        }

        if (jsCode.contains("JSON.parse(")) {
            usesJsonParse = true;
        }

        jsCode = jsCode.replaceAll("\\bconsole\\.log\\((.+?)\\)",   "getLogger().info($1)");
        jsCode = jsCode.replaceAll("\\bconsole\\.error\\((.+?)\\)", "getLogger().severe($1)");
        jsCode = jsCode.replaceAll("\\bconsole\\.warn\\((.+?)\\)",  "getLogger().warning($1)");

        if (jsCode.contains("description(")) {
            usesDescription = true;
        }

        Pattern interpolationPattern = Pattern.compile("`([^`]*)\\$\\{(.*?)\\}([^`]*)`");
        Matcher interpolationMatcher = interpolationPattern.matcher(jsCode);
        StringBuffer interpolatedResult = new StringBuffer();
        while (interpolationMatcher.find()) {
            String before = interpolationMatcher.group(1);
            String expr   = interpolationMatcher.group(2);
            String after  = interpolationMatcher.group(3);
            String replacement = "\"" + before + "\" + (" + expr + ") + \"" + after + "\"";
            interpolationMatcher.appendReplacement(interpolatedResult, Matcher.quoteReplacement(replacement));
        }
        interpolationMatcher.appendTail(interpolatedResult);
        jsCode = interpolatedResult.toString();

        Pattern sliceJoin = Pattern.compile("let\\s+([a-zA-Z0-9_]+)\\s*=\\s*args\\.slice\\((\\d+)\\)\\.join\\(\"([^\"]*)\"\\);");
        Matcher sliceJoinM = sliceJoin.matcher(jsCode);
        StringBuffer sbSliceJoin = new StringBuffer();
        while (sliceJoinM.find()) {
            String varName   = sliceJoinM.group(1);
            String fromIndex = sliceJoinM.group(2);
            String sep       = sliceJoinM.group(3);

            String replacement = "String " + varName
                    + " = String.join(\"" + sep + "\", java.util.Arrays.copyOfRange(args, "
                    + fromIndex + ", args.length));";
            sliceJoinM.appendReplacement(sbSliceJoin, replacement);
        }
        sliceJoinM.appendTail(sbSliceJoin);
        jsCode = sbSliceJoin.toString();

        Pattern dataGetArrayPattern = Pattern.compile("let\\s+([a-zA-Z0-9_]+)\\s*=\\s*data\\.getArray\\(");
        Matcher dgaMatcher = dataGetArrayPattern.matcher(jsCode);
        StringBuffer sbDga = new StringBuffer();
        while (dgaMatcher.find()) {
            String varName = dgaMatcher.group(1);
            knownListVars.add(varName);
            String replacement = "List<String> " + varName + " = data.getArray(";
            dgaMatcher.appendReplacement(sbDga, replacement);
        }
        dgaMatcher.appendTail(sbDga);
        jsCode = sbDga.toString();

        Pattern getServerPattern = Pattern.compile("let\\s+([a-zA-Z0-9_]+)\\s*=\\s*sender\\.getServer\\(\\);");
        Matcher gspMatcher = getServerPattern.matcher(jsCode);
        StringBuffer sbGs = new StringBuffer();
        while (gspMatcher.find()) {
            String varName   = gspMatcher.group(1);
            String replacement = "Server " + varName + " = sender.getServer();";
            gspMatcher.appendReplacement(sbGs, replacement);
        }
        gspMatcher.appendTail(sbGs);
        jsCode = sbGs.toString();

        Pattern getPlayerExactPattern = Pattern.compile("let\\s+([a-zA-Z0-9_]+)\\s*=\\s*([a-zA-Z0-9_]+)\\.getPlayerExact\\(");
        Matcher gpeMatcher = getPlayerExactPattern.matcher(jsCode);
        StringBuffer sbGpe = new StringBuffer();
        while (gpeMatcher.find()) {
            String varName   = gpeMatcher.group(1);
            String serverVar = gpeMatcher.group(2);
            String replacement = "Player " + varName + " = " + serverVar + ".getPlayerExact(";
            gpeMatcher.appendReplacement(sbGpe, replacement);
        }
        gpeMatcher.appendTail(sbGpe);
        jsCode = sbGpe.toString();

        Pattern getPlayerPattern = Pattern.compile("let\\s+([a-zA-Z0-9_]+)\\s*=\\s*([a-zA-Z0-9_]+)\\.getPlayer\\(\\s*\\);");
        Matcher gpMatcher = getPlayerPattern.matcher(jsCode);
        StringBuffer sbGp = new StringBuffer();
        while (gpMatcher.find()) {
            String varName = gpMatcher.group(1);
            String eventVar= gpMatcher.group(2);
            String replacement = "Player " + varName + " = " + eventVar + ".getPlayer();";
            gpMatcher.appendReplacement(sbGp, replacement);
        }
        gpMatcher.appendTail(sbGp);
        jsCode = sbGp.toString();

        Pattern ifNotVar = Pattern.compile("if \\(!([a-zA-Z0-9_]+)\\)");
        Matcher matchIfNot = ifNotVar.matcher(jsCode);
        StringBuffer sbIfNot = new StringBuffer();
        while (matchIfNot.find()) {
            String varName = matchIfNot.group(1);
            String replacement = "if (" + varName + " == null || " + varName + ".isEmpty())";
            matchIfNot.appendReplacement(sbIfNot, replacement);
        }
        matchIfNot.appendTail(sbIfNot);
        jsCode = sbIfNot.toString();

        Pattern tripleEqualsPattern = Pattern.compile(
                "([a-zA-Z0-9_]+)\\.toLowerCase\\(\\)\\s*===\\s*([a-zA-Z0-9_]+)\\.toLowerCase\\(\\)"
        );
        Matcher equalsMatcher = tripleEqualsPattern.matcher(jsCode);
        StringBuffer sbEquals = new StringBuffer();
        while (equalsMatcher.find()) {
            String leftVar  = equalsMatcher.group(1);
            String rightVar = equalsMatcher.group(2);
            String replacement = leftVar + ".toLowerCase().equals(" + rightVar + ".toLowerCase())";
            equalsMatcher.appendReplacement(sbEquals, replacement);
        }
        equalsMatcher.appendTail(sbEquals);
        jsCode = sbEquals.toString();

        jsCode = jsCode.replaceAll("\\.push\\(", ".add(");

        jsCode = transformDataSetArrays(jsCode);

        {
            Pattern splitPattern = Pattern.compile(
                    "let\\s+([a-zA-Z0-9_]+)\\s*=\\s*([a-zA-Z0-9_]+)\\.split\\(\\\"([^\\\"]+)\\\"\\)\\s*;"
            );
            Matcher splitM = splitPattern.matcher(jsCode);
            StringBuffer sbSplit = new StringBuffer();
            while (splitM.find()) {
                String varName = splitM.group(1);
                String source  = splitM.group(2);
                String delim   = splitM.group(3);

                String replacement = "String[] " + varName + " = " + source + ".split(\"" + delim + "\");";
                splitM.appendReplacement(sbSplit, replacement);
            }
            splitM.appendTail(sbSplit);
            jsCode = sbSplit.toString();
        }

        jsCode = transformForLoopListAccess(jsCode);

        Pattern fetchPattern = Pattern.compile("let\\s+([a-zA-Z0-9_]+)\\s*=\\s*fetch\\(([^)]+)\\);?");
        Matcher fetchM = fetchPattern.matcher(jsCode);
        StringBuffer sbFetch = new StringBuffer();
        while (fetchM.find()) {
            String varName = fetchM.group(1);
            String urlExpr = fetchM.group(2).trim();
            String replacement = "String " + varName + " = fetch(" + urlExpr + ");";
            fetchM.appendReplacement(sbFetch, replacement);
        }
        fetchM.appendTail(sbFetch);
        jsCode = sbFetch.toString();

        Pattern jsonParsePattern = Pattern.compile("let\\s+([a-zA-Z0-9_]+)\\s*=\\s*JSON\\.parse\\(([^)]+)\\);?");
        Matcher jsonParseM = jsonParsePattern.matcher(jsCode);
        StringBuffer sbJsonParse = new StringBuffer();
        while (jsonParseM.find()) {
            String varName  = jsonParseM.group(1);
            String arg      = jsonParseM.group(2).trim();

            knownJsonObjects.add(varName);

            String replacement = "JSONObject " + varName + " = parseJson(" + arg + ");";
            jsonParseM.appendReplacement(sbJsonParse, replacement);
        }
        jsonParseM.appendTail(sbJsonParse);
        jsCode = sbJsonParse.toString();

        Pattern propertyAccessPattern = Pattern.compile("([a-zA-Z0-9_]+)\\.([a-zA-Z0-9_]+)");
        Matcher paMatcher = propertyAccessPattern.matcher(jsCode);
        StringBuffer sbPa = new StringBuffer();
        while (paMatcher.find()) {
            String objectVar = paMatcher.group(1);
            String property  = paMatcher.group(2);

            if (knownJsonObjects.contains(objectVar) && !isKnownMethod(property)) {
                String replacement = objectVar + ".get(\"" + property + "\")";
                paMatcher.appendReplacement(sbPa, replacement);
            } else {

                paMatcher.appendReplacement(sbPa, paMatcher.group(0));
            }
        }
        paMatcher.appendTail(sbPa);
        jsCode = sbPa.toString();

        jsCode = jsCode.replaceAll("\\blet\\s+", "String ");

        {
            Pattern arrayEmptyPattern = Pattern.compile("String\\s+([a-zA-Z0-9_]+)\\s*=\\s*\\[\\]\\s*;");
            Matcher arrayEmptyMatcher = arrayEmptyPattern.matcher(jsCode);
            StringBuffer sbArrayEmpty = new StringBuffer();
            while (arrayEmptyMatcher.find()) {
                String varName = arrayEmptyMatcher.group(1);
                String replacement = "List<String> " + varName + " = new ArrayList<>();";
                arrayEmptyMatcher.appendReplacement(sbArrayEmpty, replacement);
            }
            arrayEmptyMatcher.appendTail(sbArrayEmpty);
            jsCode = sbArrayEmpty.toString();
        }

        {
            Pattern lengthEmptyPattern = Pattern.compile("if\\s*\\(\\s*([a-zA-Z0-9_]+)\\.length\\s*===\\s*0\\s*\\)");
            Matcher lengthEmptyMatcher = lengthEmptyPattern.matcher(jsCode);
            StringBuffer sbLengthEmpty = new StringBuffer();
            while (lengthEmptyMatcher.find()) {
                String varName = lengthEmptyMatcher.group(1);
                String replacement = "if (" + varName + ".isEmpty())";
                lengthEmptyMatcher.appendReplacement(sbLengthEmpty, replacement);
            }
            lengthEmptyMatcher.appendTail(sbLengthEmpty);
            jsCode = sbLengthEmpty.toString();
        }

        jsCode = handleInlineJavaBlocks(jsCode);

        return jsCode;
    }

    private static String handleInlineJavaBlocks(String code) {
        Matcher matcher = JAVA_BLOCK_PATTERN.matcher(code);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String javaCode = matcher.group(1).trim();

            javaCode = javaCode.replaceAll("(?m)^", "        ");

            javaCode = javaCode.replace("\\", "\\\\").replace("$", "\\$");
            matcher.appendReplacement(sb, Matcher.quoteReplacement(javaCode));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static boolean isKnownMethod(String name) {

        return Set.of("toLowerCase","toUpperCase","split","equals",
                "substring","toString","isEmpty","size",
                "length","add","push","replaceAll","join").contains(name);
    }

    private static String transformDataSetArrays(String code) {
        Pattern arrayPattern = Pattern.compile("data\\.set\\(\\\"([^\\\"]+)\\\"\\s*,\\s*\\[([^\\]]+)\\]\\)");
        Matcher arrayMatcher = arrayPattern.matcher(code);

        StringBuffer buffer = new StringBuffer();
        while (arrayMatcher.find()) {
            String key = arrayMatcher.group(1);
            String arrayContent = arrayMatcher.group(2).trim();

            String[] items = arrayContent.split(",");
            StringBuilder asList = new StringBuilder("java.util.Arrays.asList(");
            for (int i = 0; i < items.length; i++) {
                if (i > 0) asList.append(", ");
                asList.append(items[i].trim());
            }
            asList.append(")");

            String replacement = String.format("data.setArray(\"%s\", %s)", key, asList);
            arrayMatcher.appendReplacement(buffer, replacement);
        }
        arrayMatcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String transformForLoopListAccess(String code) {
        Pattern forLoopPattern = Pattern.compile(
                "for\\s*\\(let\\s+(\\w+)\\s*=\\s*(\\d+);\\s*\\1\\s*<\\s*([a-zA-Z0-9_]+)\\.length;\\s*\\1\\+\\+\\)\\s*\\{"
        );
        Matcher forM = forLoopPattern.matcher(code);
        StringBuffer sbFor = new StringBuffer();
        while (forM.find()) {
            String loopVar = forM.group(1);
            String initVal = forM.group(2);
            String listVar = forM.group(3);

            String replacement = "for (int " + loopVar + " = " + initVal
                    + "; " + loopVar + " < " + listVar + ".size(); " + loopVar + "++) {";
            forM.appendReplacement(sbFor, replacement);
        }
        forM.appendTail(sbFor);
        code = sbFor.toString();

        Pattern bracketPattern = Pattern.compile("([a-zA-Z0-9_]+)\\s*\\[\\s*([a-zA-Z0-9_]+)\\s*\\]");
        Matcher bracketM = bracketPattern.matcher(code);
        StringBuffer sbBracket = new StringBuffer();
        while (bracketM.find()) {
            String listName = bracketM.group(1);
            String indexVar = bracketM.group(2);

            if (knownListVars.contains(listName)) {
                String replacement = listName + ".get(" + indexVar + ")";
                bracketM.appendReplacement(sbBracket, replacement);
            } else {
                bracketM.appendReplacement(sbBracket, bracketM.group(0));
            }
        }
        bracketM.appendTail(sbBracket);
        code = sbBracket.toString();

        return code;
    }

    public static void generatePluginYml() {
        String pluginYml = """
            name: %s
            version: %s
            main: %s.Main
            commands:
            %s
            """.formatted(pluginName, pluginVersion, pluginPackage, getIndentedCommands());

        try {
            Path outputDir = Paths.get("output");
            Files.createDirectories(outputDir);
            Path pluginYmlPath = Paths.get(outputDir.toString(), "plugin.yml");
            Files.writeString(pluginYmlPath, pluginYml);
        } catch (IOException e) {
            System.err.println("Error guardando plugin.yml: " + e.getMessage());
        }
    }

    private static String getIndentedCommands() {
        StringBuilder indentedCommands = new StringBuilder();
        String[] commands = pluginCommands.toString().split("\n");
        for (String command : commands) {
            indentedCommands.append("  ").append(command).append("\n");
        }
        return indentedCommands.toString();
    }

    public static void generateCommandClasses() {

    }

    public static void generateMainClass(String jsCode) {
        String onCommandCode = generateOnCommand(jsCode);
        String eventCode     = processEvents(jsCode);

        StringBuilder imports = new StringBuilder();
        imports.append("import org.bukkit.command.Command;\n");
        imports.append("import org.bukkit.command.CommandExecutor;\n");
        imports.append("import org.bukkit.command.CommandSender;\n");
        imports.append("import org.bukkit.plugin.java.JavaPlugin;\n");
        imports.append("import org.bukkit.event.EventHandler;\n");
        imports.append("import org.bukkit.event.Listener;\n");
        imports.append("import org.bukkit.event.player.*;\n");
        imports.append("import org.bukkit.event.block.*;\n");
        imports.append("import org.bukkit.event.entity.*;\n");

        if (usesData || usesJsonParse) {
            imports.append("import org.json.simple.JSONArray;\n");
            imports.append("import java.nio.file.Files;\n");
            imports.append("import java.nio.file.Path;\n");
            imports.append("import java.nio.file.Paths;\n");
            imports.append("import java.io.FileWriter;\n");
            imports.append("import java.io.FileReader;\n");
            imports.append("import org.json.simple.JSONObject;\n");
            imports.append("import org.json.simple.parser.JSONParser;\n");
            imports.append("import org.json.simple.parser.ParseException;\n");
        }

        if (usesData || usesJsonParse || usesFetch) {
            imports.append("import java.nio.file.Files;\n");
            imports.append("import java.nio.file.Path;\n");
            imports.append("import java.nio.file.Paths;\n");
            imports.append("import java.io.FileWriter;\n");
            imports.append("import java.io.FileReader;\n");
            imports.append("import java.util.ArrayList;\n");
            imports.append("import java.util.List;\n");
            imports.append("import java.io.File;\n");
            imports.append("import java.io.IOException;\n");
            imports.append("import org.bukkit.Server;\n");
            imports.append("import org.bukkit.entity.Player;\n");
        }

        if (usesFetch) {
            imports.append("import java.net.http.HttpClient;\n");
            imports.append("import java.net.http.HttpRequest;\n");
            imports.append("import java.net.http.HttpResponse;\n");
            imports.append("import java.net.URI;\n");
        }

        String dataHandlerClass = usesData
                ? generateDataHandlerClassLiteral(pluginName, dataFileName)
                : "";

        String descriptionMethod = usesDescription ? generateDescriptionMethod() : "";

        String fetchMethod = "";
        if (usesFetch) {
            fetchMethod = """
                public static String fetch(String url) {
                    try {
                        HttpClient client = HttpClient.newHttpClient();
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .build();
                        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        return response.body();
                    } catch (Exception e) {
                        e.printStackTrace();
                        return "";
                    }
                }
            """;
        }

        String parseJsonMethod = "";
        if (usesJsonParse) {
            parseJsonMethod = """
                public static JSONObject parseJson(String jsonText) {
                    try {
                        JSONParser parser = new JSONParser();
                        Object obj = parser.parse(jsonText);
                        if (obj instanceof JSONObject jsonObj) {
                            return jsonObj;
                        } else {

                            return new JSONObject();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        return new JSONObject();
                    }
                }
            """;
        }

        String mainClass = String.format("""
            package %s;

            %s

            public class Main extends JavaPlugin implements CommandExecutor, Listener {

                %s

                @Override
                public void onEnable() {
                    getLogger().info("Plugin enabled!");
                    getServer().getPluginManager().registerEvents(this, this);
                }

                %s

                %s

                %s
                %s
            }
            """,
                pluginPackage,
                imports.toString(),
                dataHandlerClass + "\n" + descriptionMethod,
                onCommandCode,
                eventCode,
                fetchMethod,
                parseJsonMethod
        );

        try {
            Path packageDir = Paths.get("output", pluginPackage.replace(".", "/"));
            Files.createDirectories(packageDir);
            Files.writeString(packageDir.resolve("Main.java"), mainClass);
        } catch (IOException e) {
            System.err.println("Error saving Main class: " + e.getMessage());
        }
    }

    private static String processEvents(String jsCode) {
        StringBuilder eventMethods = new StringBuilder();
        Pattern eventPattern = Pattern.compile(
                "event\\(\\s*\"([^\"]+)\"\\s*,\\s*\\(([^)]+)\\)\\s*=>\\s*\\{(.*?)\\}\\s*\\)",
                Pattern.DOTALL
        );
        Matcher matcher = eventPattern.matcher(jsCode);

        while (matcher.find()) {
            String eventName  = matcher.group(1);
            String eventParam = matcher.group(2);
            String eventBody  = matcher.group(3).trim();

            String eventClass   = mapEventNameToClass(eventName);
            String eventPackage = getEventPackage(eventName);
            String methodName   = "on" + capitalize(eventName);

            eventMethods.append(String.format("""
                @EventHandler
                public void %s(%s.%s %s) {
                    %s
                }

                """,
                    methodName,
                    eventPackage,
                    eventClass,
                    eventParam,
                    eventBody
            ));
        }

        return eventMethods.toString();
    }

    private static String generateOnCommand(String jsCode) {
        StringBuilder sb = new StringBuilder();
        sb.append("@Override\n");
        sb.append("public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {\n\n");

        Pattern cmdPattern = Pattern.compile(
                "command\\(\\s*\"([^\"]+)\"\\s*,\\s*\\([^)]*\\)\\s*=>\\s*\\{(.*?)\\}\\s*\\)",
                Pattern.DOTALL
        );
        Matcher cmdMatcher = cmdPattern.matcher(jsCode);

        while (cmdMatcher.find()) {
            String cmdName = cmdMatcher.group(1);
            String body    = cmdMatcher.group(2);

            body = body.replaceAll("console\\.log\\(([^)]+)\\);?",   "getLogger().info($1);");
            body = body.replaceAll("console\\.error\\(([^)]+)\\);?", "getLogger().severe($1);");
            body = body.replaceAll("console\\.warn\\(([^)]+)\\);?",  "getLogger().warning($1);");
            body = body.replaceAll("return;",                        "return true;");

            sb.append("    if (command.getName().equalsIgnoreCase(\"")
                    .append(cmdName)
                    .append("\")) {\n");

            String[] lines = body.split("\n");
            for (String line : lines) {
                sb.append("        ").append(line).append("\n");
            }
            sb.append("        return true;\n");
            sb.append("    }\n\n");
        }

        sb.append("    return false;\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String mapEventNameToClass(String eventName) {
        Map<String, String> eventMap = new HashMap<>();
        eventMap.put("playerJoin", "PlayerJoinEvent");
        eventMap.put("playerQuit", "PlayerQuitEvent");
        eventMap.put("blockBreak", "BlockBreakEvent");
        eventMap.put("blockPlace", "BlockPlaceEvent");
        eventMap.put("entityDamage", "EntityDamageEvent");

        return eventMap.getOrDefault(eventName, "Event");
    }

    private static String getEventPackage(String eventName) {
        if (eventName.startsWith("player")) {
            return "org.bukkit.event.player";
        } else if (eventName.startsWith("block")) {
            return "org.bukkit.event.block";
        } else if (eventName.startsWith("entity")) {
            return "org.bukkit.event.entity";
        }
        return "org.bukkit.event";
    }

    public static void compileJavaFiles() {
        try {
            List<String> javaFiles = new ArrayList<>();
            javaFiles.add("output/" + pluginPackage.replace(".", "/") + "/Main.java");

            String spigotJarPath = "C:/spigot/spigot.jar";
            String jsonJarPath   = "C:/spigot/json-simple.jar";

            List<String> command = new ArrayList<>();
            command.add("javac");
            command.add("-verbose");
            command.add("-d");
            command.add("output");
            command.add("-cp");

            String classpath = "output" + File.pathSeparator + spigotJarPath;
            if (usesData || usesJsonParse) {
                classpath += File.pathSeparator + jsonJarPath;
            }
            command.add(classpath);

            command.addAll(javaFiles);

            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("Compilation: " + line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                System.err.println("Compilation failed with exit code: " + exitCode);
            }

        } catch (Exception e) {
            System.err.println("Error compiling classes: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void createJar(String outputJar) {
        try {
            Path jarPath = Paths.get("output", outputJar);

            File outputDir = new File("output");
            if (!outputDir.exists() || !outputDir.isDirectory()) {
                System.err.println("El directorio 'output' no existe.");
                return;
            }

            Process process = new ProcessBuilder(
                    "jar",
                    "cf",
                    jarPath.toString(),
                    "-C", "output",
                    "."
            ).start();

            process.waitFor();
            if (process.exitValue() == 0) {
                System.out.println("Archivo .jar creado en: " + jarPath);
            } else {
                System.err.println("Error creando el archivo .jar.");
            }

        } catch (Exception e) {
            System.err.println("Error creando el archivo .jar: " + e.getMessage());
        }
    }

    public static void deleteUnwantedJavaFiles() {
        try {
            Files.walk(Paths.get("output"))
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            System.err.println("Error eliminando archivo: " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            System.err.println("Error limpiando archivos Java no deseados: " + e.getMessage());
        }
    }

    public static void showElapsedTime() {
        long endTime = System.currentTimeMillis();
        long elapsedMillis = endTime - startTime;

        long seconds = (elapsedMillis / 1000) % 60;
        long minutes = (elapsedMillis / (1000 * 60)) % 60;
        long hours   = (elapsedMillis / (1000 * 60 * 60)) % 24;

        System.out.println("\nTiempo total de compilación: "
                + hours + " horas, "
                + minutes + " minutos, "
                + seconds + " segundos.");
    }

    public static void updateProgress(String message) {
        long elapsedTime = System.currentTimeMillis() - startTime;
        double progress = calculateProgress(message);

        System.out.print("\r" + getProgressBar(progress) + " " + (int)(progress * 100) + "% " + getShortMessage(message));

        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static double calculateProgress(String message) {
        return switch (message) {
            case "Convirtiendo clases."   -> 0.1;
            case "Convirtiendo clases.."  -> 0.4;
            case "Convirtiendo clases..." -> 0.6;
            case "Compilando clases."     -> 0.8;
            case "Compilando clases.."    -> 0.9;
            case "Generando .jar"         -> 1.0;
            default -> 1.0;
        };
    }

    private static String getProgressBar(double progress) {
        int totalBars = 10;
        int progressBars = (int) (progress * totalBars);
        StringBuilder progressBar = new StringBuilder();
        for (int i = 0; i < totalBars; i++) {
            if (i < progressBars) {
                progressBar.append("=");
            } else {
                progressBar.append("-");
            }
        }
        return "[" + progressBar + "]";
    }

    private static String getShortMessage(String message) {
        String shortMessage = message.length() > 20 ? message.substring(0, 20) : message;
        while (shortMessage.length() < 20) {
            shortMessage += " ";
        }
        return shortMessage;
    }

    private static String capitalize(String input) {
        if (input == null || input.isEmpty()) return input;
        return input.substring(0, 1).toUpperCase() + input.substring(1).toLowerCase();
    }

    private static String generateDescriptionMethod() {
        return """
            public void description(String msg) {

            }
            """;
    }

    private static String generateDataHandlerClassLiteral(String pluginName, String dataFileName) {
        return """
            private static final DataHandler data = new DataHandler();

            public static class DataHandler {
                private static final String DATA_FILE = "%s";
                private static JSONObject jsonObject = new JSONObject();

                public DataHandler() {
                    loadData();
                }

                public void set(String key, Object value) {
                    jsonObject.put(key, value);
                    saveData();
                }

                public Object get(String key) {
                    return jsonObject.get(key);
                }

                public String getString(String key) {
                    Object val = jsonObject.get(key);
                    return val == null ? null : val.toString();
                }

                @SuppressWarnings("unchecked")
                public void setArray(String key, List<String> list) {
                    JSONArray array = new JSONArray();
                    array.addAll(list);
                    jsonObject.put(key, array);
                    saveData();
                }

                @SuppressWarnings("unchecked")
                public List<String> getArray(String key) {
                    Object val = jsonObject.get(key);
                    if (val instanceof JSONArray arr) {
                        List<String> result = new ArrayList<>();
                        for (Object o : arr) {
                            result.add(o.toString());
                        }
                        return result;
                    }
                    return new ArrayList<>();
                }

                private void loadData() {
                    File folder = new File("plugins", "%s");
                    folder.mkdirs();

                    File file = new File(folder, DATA_FILE);
                    if (!file.exists()) {
                        jsonObject = new JSONObject();
                        saveData();
                        return;
                    }
                    try {
                        String content = Files.readString(file.toPath());
                        JSONParser parser = new JSONParser();
                        jsonObject = (JSONObject) parser.parse(content);
                    } catch (IOException | ParseException e) {
                        e.printStackTrace();
                        jsonObject = new JSONObject();
                    }
                }

                private void saveData() {
                    File folder = new File("plugins", "%s");
                    folder.mkdirs();

                    File file = new File(folder, DATA_FILE);
                    try {
                        Files.writeString(file.toPath(), jsonObject.toJSONString());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            """.formatted(dataFileName, pluginName, pluginName);
    }
}
