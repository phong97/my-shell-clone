package org.my.shell;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public class Main {
    private static final String[] BUILTIN_COMMANDS = { "echo", "type", "pwd", "cd", "exit" };

    enum Command {
        ECHO("echo"),
        TYPE("type"),
        PWD("pwd"),
        CD("cd"),
        EXIT("exit"),
        UNKNOWN("");

        private final String name;

        Command(String name) {
            this.name = name;
        }

        public static Command fromString(String commandStr) {
            for (Command cmd : values()) {
                if (cmd.name.equals(commandStr)) {
                    return cmd;
                }
            }
            return UNKNOWN;
        }

        public boolean isBuiltin() {
            return this != UNKNOWN;
        }
    }

    enum RedirectType {
        NONE("none"),
        STDOUT("stdout"),
        STDOUT_APPEND("stdout_append"),
        STDERR("stderr"),
        STDERR_APPEND("stderr_append");

        private final String name;

        RedirectType(String name) {
            this.name = name;
        }

        public static RedirectType fromOperator(String operator) {
            if (operator == null) {
                return NONE;
            }

            switch (operator) {
                case ">":
                case "1>":
                    return STDOUT;
                case ">>":
                case "1>>":
                    return STDOUT_APPEND;
                case "2>":
                    return STDERR;
                case "2>>":
                    return STDERR_APPEND;
                default:
                    return NONE;
            }
        }

        public boolean isRedirecting() {
            return this != NONE;
        }

        public boolean isRedirectingStdout() {
            return this == STDOUT || this == STDOUT_APPEND;
        }

        public boolean isRedirectingStderr() {
            return this == STDERR || this == STDERR_APPEND;
        }

        public boolean isAppending() {
            return this == STDOUT_APPEND || this == STDERR_APPEND;
        }
    }

    public static void main(String[] args) throws Exception {
        Terminal terminal = TerminalBuilder.builder()
                .name("Shell")
                .system(true)
                .build();

        String[] allCommands = getAllExecutableCommands();

        LineReader lineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new StringsCompleter(allCommands))
                .build();

        String prompt = "$ ";
        String line;

        while ((line = lineReader.readLine(prompt)) != null) {
            if (line.equals("exit 0")) {
                break;
            }

            if (line.trim().isEmpty()) {
                continue;
            }

            String[] listCMD = parseCommandLine(line);

            if (listCMD.length == 0) {
                continue;
            }

            if (isRedirectingOutput(listCMD)) {
                handleRedirectingOutput(line);
                continue;
            }

            String cmdName = listCMD[0];
            Command cmd = Command.fromString(cmdName);

            switch (cmd) {
                case ECHO:
                    handleEchoCommand(listCMD);
                    break;

                case TYPE:
                    handleTypeCommand(listCMD);
                    break;

                case PWD:
                    System.out.println(System.getProperty("user.dir"));
                    break;

                case CD:
                    handleCdCommand(listCMD);
                    break;

                case UNKNOWN:
                    if (!executeExternalCommand(listCMD)) {
                        System.out.println(cmdName + ": command not found");
                    }
                    break;
            }
        }
    }

    public static boolean isRedirectingOutput(String[] listCMD) {
        return findRedirectOperatorIndex(listCMD) != -1;
    }

    public static int findRedirectOperatorIndex(String[] listCMD) {
        for (int i = 0; i < listCMD.length; i++) {
            if (listCMD[i].equals(">") || listCMD[i].equals("1>") || listCMD[i].equals("2>") ||
                    listCMD[i].equals(">>") || listCMD[i].equals("1>>") || listCMD[i].equals("2>>")) {
                return i;
            }
        }
        return -1;
    }

    public static void handleRedirectingOutput(String input) {
        String[] originalListCMD = parseCommandLine(input);
        int redirectIndex = findRedirectOperatorIndex(originalListCMD);

        if (redirectIndex == -1 || redirectIndex >= originalListCMD.length - 1) {
            executeExternalCommand(originalListCMD);
            return;
        }

        RedirectType redirectType = RedirectType.fromOperator(originalListCMD[redirectIndex]);
        String outputFile = originalListCMD[redirectIndex + 1];

        // Extract just the command part (before the redirection)
        String[] command = new String[redirectIndex];
        System.arraycopy(originalListCMD, 0, command, 0, redirectIndex);

        executeExternalCommand(command, redirectType, outputFile);
    }

    public static boolean executeExternalCommand(String[] command, RedirectType redirectType, String outputFile) {
        if (command.length == 0)
            return false;

        String commandName = command[0];
        String executablePath = findExecutable(commandName);
        if (executablePath == null)
            return false;

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
            processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);

            if (redirectType.isRedirectingStdout()) {
                if (redirectType.isAppending()) {
                    processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(outputFile)));
                } else {
                    processBuilder.redirectOutput(new File(outputFile));
                }
            } else if (redirectType.isRedirectingStderr()) {
                if (redirectType.isAppending()) {
                    processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(new File(outputFile)));
                } else {
                    processBuilder.redirectError(new File(outputFile));
                }
            }

            Process process = processBuilder.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            System.out.println("Error executing command: " + e.getMessage());
        }

        return false;
    }

    public static boolean executeExternalCommand(String[] command) {
        return executeExternalCommand(command, RedirectType.NONE, null);
    }

    public static String[] parseCommandLine(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder currentToken = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        boolean escaping = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (escaping) {
                // Handle escaped characters in double quotes
                if (inDoubleQuotes) {
                    // Only these characters have special meaning when escaped in double quotes
                    if (c == '\\' || c == '$' || c == '"' || c == '\n') {
                        currentToken.append(c);
                    } else {
                        // For other characters, the backslash is preserved
                        currentToken.append('\\');
                        currentToken.append(c);
                    }
                } else {
                    // Outside of quotes, any escaped character is taken literally
                    currentToken.append(c);
                }
                escaping = false;
                continue;
            }

            // Handle escape character
            if (c == '\\' && !inSingleQuotes) {
                escaping = true;
                continue;
            }

            // Handle quotes
            if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
                continue;
            }

            if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
                continue;
            }

            // Handle spaces (token separators)
            if (c == ' ' && !inSingleQuotes && !inDoubleQuotes) {
                if (currentToken.length() > 0) {
                    tokens.add(currentToken.toString());
                    currentToken.setLength(0);
                }
                continue;
            }

            // Add all other characters to current token
            currentToken.append(c);
        }

        // Add the last token if there is one
        if (currentToken.length() > 0) {
            tokens.add(currentToken.toString());
        }

        // Check for unclosed quotes
        if (inSingleQuotes) {
            System.out.println("Error: Unclosed single quote");
            return new String[0];
        }

        if (inDoubleQuotes) {
            System.out.println("Error: Unclosed double quote");
            return new String[0];
        }

        return tokens.toArray(new String[0]);
    }

    public static void handleEchoCommand(String[] listCMD) {
        if (listCMD.length == 1) {
            System.out.println("");
            return;
        }
        StringBuilder output = new StringBuilder();
        for (int i = 1; i < listCMD.length; i++) {
            if (i > 1)
                output.append(" ");
            output.append(listCMD[i]);
        }
        System.out.println(output.toString());
    }

    public static void handleCdCommand(String[] listCMD) {
        if (listCMD.length == 1) {
            System.out.println("cd: missing argument");
            return;
        }

        String dirPath = listCMD[1];
        String currentDir = System.getProperty("user.dir");

        if (dirPath.startsWith("~")) {
            String userHome = System.getenv("HOME");
            if (dirPath.equals("~")) {
                dirPath = userHome;
            } else if (dirPath.startsWith("~/")) {
                dirPath = userHome + dirPath.substring(1);
            }
        }

        // Handle absolute and relative paths
        File targetDir;
        if (dirPath.startsWith("/")) {
            targetDir = new File(dirPath);
        } else {
            targetDir = new File(currentDir, dirPath);
        }

        try {
            // Normalize path to resolve . and .. references
            targetDir = targetDir.getCanonicalFile();
        } catch (Exception e) {
            System.out.println("cd: " + dirPath + ": " + e.getMessage());
            return;
        }

        if (!targetDir.exists()) {
            System.out.println("cd: " + dirPath + ": No such file or directory");
            return;
        }

        if (!targetDir.isDirectory()) {
            System.out.println("cd: " + dirPath + ": Not a directory");
            return;
        }

        try {
            System.setProperty("user.dir", targetDir.getCanonicalPath());
        } catch (Exception e) {
            System.out.println("cd: " + dirPath + ": " + e.getMessage());
        }
    }

    public static void handleTypeCommand(String[] listCMD) {
        if (listCMD.length == 1) {
            System.out.println(": not found");
            return;
        }

        String subCmd = listCMD[1];
        Command cmd = Command.fromString(subCmd);

        if (cmd.isBuiltin()) {
            System.out.println(subCmd + " is a shell builtin");
            return;
        }

        String executablePath = findExecutable(subCmd);
        if (executablePath != null) {
            System.out.println(subCmd + " is " + executablePath);
            return;
        }

        System.out.println(subCmd + ": not found");
    }

    public static String findExecutable(String commandName) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }

        String[] directories = path.split(":");

        for (String directory : directories) {
            File file = new File(directory, commandName);
            if (file.exists() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }

        return null;
    }

    private static String[] getAllExecutableCommands() {
        Set<String> commands = new HashSet<>();

        for (String cmd : BUILTIN_COMMANDS) {
            commands.add(cmd);
        }

        String path = System.getenv("PATH");
        if (path != null) {
            String[] directories = path.split(":");

            for (String directory : directories) {
                File dir = new File(directory);
                if (dir.exists() && dir.isDirectory()) {
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File file : files) {
                            if (file.canExecute() && !file.isDirectory()) {
                                commands.add(file.getName());
                            }
                        }
                    }
                }
            }
        }

        return commands.toArray(new String[0]);
    }
}
