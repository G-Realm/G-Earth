package gearth.protocol.hostreplacer.hostsfile;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

class UnixHostReplacer implements HostReplacer {

    protected String hostsFileLocation;

    UnixHostReplacer() {
    }

    private String cwd() {
        String filePath;
        try {
            filePath = new File(this.getClass().getProtectionDomain().getCodeSource().getLocation().toURI())
                    .getParent();
        } catch (Exception e) {
            return ".";
        }
        return filePath;
    }

    @Override
    public void addRedirect(String[] lines) {

        String filePath = cwd();
        ProcessBuilder builder = new ProcessBuilder();
        List<String> command = new ArrayList<String>();
        command.add("pkexec");
        command.add(filePath + "/modhost");
        command.add("a");
        for (String line : lines) {
            command.add(line);
        }

        builder.command(command);

        try {
            Process p = builder.start();
            p.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void removeRedirect(String[] lines) {
        ProcessBuilder builder = new ProcessBuilder();
        List<String> command = new ArrayList<String>();

        String filePath = cwd();
        command.add("pkexec");
        command.add(filePath + "/modhost");
        command.add("d");
        builder.command(command);
        try {
            Process p = builder.start();
            p.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
