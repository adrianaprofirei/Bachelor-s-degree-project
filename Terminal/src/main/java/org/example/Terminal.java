package org.example;

import com.sun.javacard.apduio.Apdu;
import com.sun.javacard.apduio.CadClientInterface;
import com.sun.javacard.apduio.CadDevice;
import com.sun.javacard.apduio.CadTransportException;

import java.io.*;
import java.net.Socket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

public class Terminal {
    Process process;
    String crefFilePath = "C:\\Program Files (x86)\\Oracle\\Java Card Development Kit Simulator 3.1.0\\bin\\cref.bat";
    String scriptFilePath = "C:\\Users\\Adrian\\eclipse-workspace\\ACSC\\apdu_scripts\\cap-ACSC.script";

    public void open_process() {
        try {
            process = Runtime.getRuntime().exec(crefFilePath);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void connection() {
        String host = "localhost";
        int port = 9025;
        Socket socket = null;
        OutputStream output = null;
        InputStream input = null;
        Apdu apdu;
        CadClientInterface cad;

        try {
            socket = new Socket("localhost", 9025);
            output = socket.getOutputStream();
            input = socket.getInputStream();
        } catch (IOException e) {
            System.out.println("Cannot create socket to + " + host + ":" + port);
            System.out.println("Cannot obtain I/O from socket (" + host + ":" + port + ")");
            e.printStackTrace();
        }

        cad = CadDevice.getCadClientInstance(CadDevice.PROTOCOL_T1, input, output);
        try {
            cad.powerUp();
        } catch (CadTransportException | IOException e) {
            e.printStackTrace();
        }
        apdu = new Apdu();
        try (BufferedReader br = new BufferedReader(new FileReader(scriptFilePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("//") || line.isEmpty()) {
                    continue;
                }
                processLine(cad, line);
            }
            parseCommands(apdu, cad);
        } catch (IOException | NumberFormatException e) {
            e.printStackTrace();
        } finally {
            try {
                cad.powerDown();
                socket.close();
            } catch (IOException | CadTransportException e) {
                e.printStackTrace();
            }
        }
    }

    private static final Map<Integer, Integer> requiredHoursPerZone = new HashMap<>();

    static {
        requiredHoursPerZone.put(0, 120);
        requiredHoursPerZone.put(1, 120);
    }

    private static final LocalDate SEMESTER_START = LocalDate.of(2024, 2, 26);
    private static final LocalDate SEMESTER_END = LocalDate.of(2024, 6, 7);

    public static void parseCommands(Apdu apdu, CadClientInterface cad) throws IOException {
        String create = "0x80 0xB8 0x00 0x00 0x13 0x09 0xa0 0x0 0x0 0x0 0x88 0x18 0x05 0x18 0x59 0x08 0x0 0x0 0x05 0x01 0x02 0x03 0x04 0x05 0x7F;";
        processLine(cad, create);

        String init = "0x00 0xA4 0x04 0x00 0x09 0xa0 0x0 0x0 0x0 0x88 0x18 0x05 0x18 0x59 0x7F;";
        processLine(cad, init);

        Scanner scanner = new Scanner(System.in);
        boolean isLogged = false;
        boolean notInSemester = false;
        boolean alreadyGotAccess = false;
        boolean hasEnteredAnyZone = false;
        int studentId = 0;
        int pinFailedAttempts = 0;
        final int MAX_ATTEMPTS = 3;

        Map<Integer, Long> zoneEntryTimes = new HashMap<>();
        Map<Integer, Long> timeSpentInZones = new HashMap<>();

        System.out.println("Pentru a intra in campus, va rugam sa introduceti codul PIN.");
        System.out.println("Folositi comanda {verifyPin}");

        while (true) {
            String userChoice = scanner.nextLine();

            if (Objects.equals(userChoice, "verifyPin")) {
                if (isLogged) {
                    System.out.println("Sunteti deja autentificat.");
                }
                else {
                    System.out.println("Va rugam sa introduceti PIN-ul dumneavoastra:");

                    String pin = scanner.nextLine();
                    if (pin == null || pin.trim().isEmpty()) {
                        System.out.println("PIN-ul nu poate avea o lungime vida.");
                    }
                    StringBuilder command = new StringBuilder("0x80 0x20 0x00 0x00 0x0" + pin.length());
                    for (int i = 0; i < pin.length(); i++) {
                        command.append(" " + "0x").append(charToIntBase16(pin.charAt(i)));
                    }

                    command.append(" 0x7F;");
                    Apdu result = processLine(cad, command.toString());
                    String status = byteArrayToHexString(result.getSw1Sw2());

                    SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:dd:MM:yyyy");
                    String formattedDate = sdf.format(new Date());

                    if (Objects.equals(status, "9000")) {
                        String studentName = getStudentName(studentId);
                        System.out.println("Bine ati venit! <" + studentName + " " + formattedDate + ">");
                        System.out.println("Pentru a obtine lista cu toate comenzile disponibile, puteti folosi " +
                                "comanda {listCommands}.");
                        isLogged = true;
                        LocalDate currentDate = LocalDate.now();
                        notInSemester = (currentDate.isBefore(SEMESTER_START) || currentDate.isAfter(SEMESTER_END));

                        command = new StringBuilder("0x80 0x50 0x00 0x00 0x17 0x01 0x01 0x01 0x01 0x00 0x00 0x01 0x00 0x00 0x00 0x00 0x00 " +
                                "0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x7F;");

                        Apdu loadStudent = processLine(cad, command.toString());
                        String loadStatusStudent = byteArrayToHexString(result.getSw1Sw2());

                        if (!Objects.equals(status, "9000")) {
                            System.out.println("Student not loaded successfully.");
                        }

                        try (Connection conn = DatabaseConnection.getConnection()) {
                            LocalDate lastUpdateDate = getLastUpdateDate(conn, studentId);
                            if (!currentDate.equals(lastUpdateDate)) {
                                resetHoursStudiedToday(conn, studentId);
                                updateLastUpdateDate(conn, studentId, currentDate);
                            }
                        } catch (SQLException e) {
                            System.err.println("An error occurred while resetting hours studied today: " + e.getMessage());
                        }
                    } else if (Objects.equals(status, "6300")) {
                        pinFailedAttempts++;
                        System.out.println("PIN incorect! Incercari ramase pana la blocarea cardului: "
                                + (MAX_ATTEMPTS - pinFailedAttempts));
                        if (pinFailedAttempts >= MAX_ATTEMPTS) {
                            System.out.println("Ati atins numarul maxim de incercari! Va rugam incercati " +
                                    "mai tarziu.");
                            System.exit(0);
                        }
                    }
                }
            } else if (Objects.equals(userChoice, "updatePin")) {
                if (!isLogged) {
                    System.out.println("PIN nu este validat! Validati PIN-ul pentru a continua.");
                }
                else {
                    System.out.println("Va rugam sa introduceti PIN-ul curent, urmat de PIN-ul nou:");
                    System.out.println("{[currentPIN] [newPIN]}");

                    String pin = scanner.nextLine();
                    int len = pin.length();
                    String[] splitPin = pin.split("\\s+");
                    String fullPin = splitPin[0] + splitPin[1];
                    StringBuilder command = new StringBuilder("0x80 0x30 0x00 0x00 0x"
                            + Integer.toHexString(len - 1));
                    for (int i = 0; i < len - 1; i++) {
                        command.append(" " + "0x").append(charToIntBase16(fullPin.charAt(i)));
                    }
                    command.append(" 0x7F;");
                    Apdu result = processLine(cad, command.toString());
                    String status = byteArrayToHexString(result.getSw1Sw2());

                    if (Objects.equals(status, "9000")) {
                        isLogged = false;
                        System.out.println("PIN-ul a fost schimbat cu succes!");
                        System.out.println("Va rugam sa introduceti PIN-ul nou pentru validarea accesului.");
                    }
                    else if (Objects.equals(status, "6300"))
                        System.out.println("PIN-ul curent introdus este incorect!.");
                    else if (Objects.equals(status, "6301"))
                        System.out.println("PIN-ul nu este validat! Validati PIN-ul pentru a continua ({verifyPin}).");
                    else if (Objects.equals(status, "6700"))
                        System.out.println("PIN-ul introdus are o lungime invalida! Lungimea acestuia este de 5 cifre.");
                }
            } else if (Objects.equals(userChoice, "checkAccess")) {
                if (!isLogged) {
                    System.out.println("PIN nu este validat! Validati PIN-ul pentru a continua.");
                } else if (!alreadyGotAccess) {
                    System.out.println("Va rugam sa introduceti zona de acces (0-8):");
                    String zone = scanner.nextLine();

                    if (zone == null || zone.trim().isEmpty()) {
                        System.out.println("Zona invalida! Va rugam incercati din nou.");
                        continue;
                    }

                    if (Integer.parseInt(zone) >= 0 && Integer.parseInt(zone) <= 8 && !zone.isEmpty()) {
                        int zoneNumber = Integer.parseInt(zone);
                        if ((zone.equals("0") || zone.equals("1")) && notInSemester) {
                            System.out.println("Nu va aflati in semestrul universitar. Accesul in zonele speciale " +
                                    "este restrictionat.");
                        }
                        else if ((zone.equals("0") || zone.equals("1")) && !isWithinSchedule(studentId, zoneNumber)) {
                            System.out.println("Nu aveti acces la zona speciala <" + zone + "> in acest interval orar.");
                        }
                            else {
                                if (hasMetRequiredHours(studentId, zoneNumber)) {
                                    System.out.println("Ati studiat numarul de ore necesar semestrul acesta in zona <" + zoneNumber + ">.");
                                } else {
                                    String command = "0x80 0x40 0x00 0x00 0x01 0x" + zone + " 0x7F;";
                                    Apdu result = processLine(cad, command);
                                    String status = byteArrayToHexString(result.getSw1Sw2());

                                    SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:dd:MM:yyyy");
                                    String formattedDate = sdf.format(new Date());

                                    if (Objects.equals(status, "9000")) {
                                        alreadyGotAccess = true;
                                        hasEnteredAnyZone = true;
                                        String studentName = getStudentName(studentId);
                                        System.out.println("Access permis <" + zone + "> <" + studentName
                                                + " " + formattedDate + ">");
                                        zoneEntryTimes.put(Integer.valueOf(zone), System.currentTimeMillis());
                                        if (zone.equals("0") || zone.equals("1")) {
                                            int requiredHours = requiredHoursPerZone.getOrDefault(zoneNumber, Integer.MAX_VALUE);
                                            long hoursStudied = getHoursStudiedForZone(studentId, zoneNumber);
                                            long remainingHours = Math.max(0, requiredHours - hoursStudied);

                                            System.out.println("Ati studiat un numar de " + hoursStudied + " ore semestrul " +
                                                    "acesta. " +  "Mai trebuie sa studiati inca " + remainingHours + " ore " +
                                                    "pentru zona speciala <" + zoneNumber + "> pentru a le recupera.");
                                        }
                                    } else if (Objects.equals(status, "6982")) {
                                        System.out.println("Access Interzis <" + zone +">");
                                    }
                                }
                            }

                    } else {
                        System.out.println("Zona introdusa trebuie sa respecte intervalul destinat acestora.");
                    }
                } else {
                    System.out.println("Nu aveti voie sa intrati in 2 sau mai multe zone deodata.");
                }
            }
            else if (Objects.equals(userChoice, "exitZone")) {
                if (!isLogged) {
                    System.out.println("PIN nu este validat! Validati PIN-ul pentru a continua.");
                } else {
                    System.out.println("Va rugam sa introduceti zona de acces (0-8) pentru iesire:");

                    String zone = scanner.nextLine();

                    if (Integer.parseInt(zone) >= 0 && Integer.parseInt(zone) <= 8) {
                        String command = "0x80 0x41 0x00 0x00 0x01 0x" + zone + " 0x7F;";
                        Apdu result = processLine(cad, command);
                        String status = byteArrayToHexString(result.getSw1Sw2());

                        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:dd:MM:yyyy");
                        String formattedDate = sdf.format(new Date());

                        if (Objects.equals(status, "9000")) {
                            alreadyGotAccess = false;
                            LocalDate dateNow = LocalDate.now();
                            String studentName = getStudentName(studentId);
                            System.out.println("Iesire din zona <" + zone + "> <" + studentName + " " + formattedDate + ">");
                            Long entryTime = zoneEntryTimes.get(Integer.valueOf(zone));
                            if (entryTime != null) {
                                long exitTime = System.currentTimeMillis();
                                long timeSpent = exitTime - entryTime;
                                timeSpentInZones.put(Integer.valueOf(zone), timeSpentInZones
                                        .getOrDefault(Integer.valueOf(zone), 0L) + timeSpent);
                                zoneEntryTimes.remove(Integer.valueOf(zone));
                            }
                            int zoneNumber = Integer.parseInt(zone);
                            int requiredHours = requiredHoursPerZone.getOrDefault(zoneNumber, Integer.MAX_VALUE);
                            long hoursStudied = getHoursStudiedForZone(studentId, zoneNumber);
                            long remainingHours = Math.max(0, requiredHours - hoursStudied);
                            if (dateNow.isEqual(SEMESTER_END) && remainingHours > 0) {
                                System.out.println("Ați studiat un număr de " + hoursStudied + " (de) ore semestrul " +
                                        "acesta.");
                                System.out.println("Mai trebuie să studiați încă " +remainingHours + " (de) ore " +
                                        "pentru " + "zona specială <" + zone + ">, insa termenul limita a expirat.");
                            }
                        } else if (Objects.equals(status, "6985")) {
                            System.out.println("Iesire interzisa din zona <" + zone + ">. Nu va aflati in aceasta zona.");
                        }
                    } else System.out.println("Zona introdusa trebuie sa respecte intervalul destinat acestora.");
                }

            } else if (Objects.equals(userChoice, "checkHours")) {
                if (!isLogged) {
                    System.out.println("PIN nu este validat! Validati PIN-ul pentru a continua.");
                } else {
                    try {
                        Connection conn = DatabaseConnection.getConnection();
                        String query = "SELECT hours_studied_semester FROM Students WHERE student_id = ?";
                        PreparedStatement stmt = conn.prepareStatement(query);
                        stmt.setInt(1, studentId);
                        ResultSet rs = stmt.executeQuery();
                        if (rs.next()) {
                            LocalTime hoursStudied = rs.getTime("hours_studied_semester").toLocalTime();
                            System.out.println("Total ore studiu in semestru: " + hoursStudied);
                        } else {
                            System.out.println("Nu s-au gasit informatii despre orele de studiu.");
                        }
                        rs.close();
                        stmt.close();
                        conn.close();
                    } catch (SQLException e) {
                        e.printStackTrace();
                        System.out.println("Eroare la interogarea bazei de date.");
                    }
                }
            }
            else if (Objects.equals(userChoice, "listCommands")) {
                System.out.println("Comenzile disponibile sunt: {verifyPin}, {updatePin}, {checkAccess}," +
                        " {checkHours}, {exitZone}, {exitCampus}.");
            }

                else if (Objects.equals(userChoice, "exitCampus")) {
                    if (!isLogged) {
                        System.out.println("PIN nu este validat! Validati PIN-ul pentru a continua.");
                    } else {
                        if (!hasEnteredAnyZone) {
                            System.out.println("La revedere! Nu ai petrecut timp in nicio zona astazi.");
                            System.exit(0);
                        }
                        else {
                            System.out.println("La revedere! Ai petrecut:");
                            saveTimeSpentInZones(studentId, timeSpentInZones);
                            for (Map.Entry<Integer, Long> entry : timeSpentInZones.entrySet()) {
                                int zone = entry.getKey();
                                long timeSpent = entry.getValue();
                                long hours = timeSpent / 3600000;
                                long minutes = (timeSpent % 3600000) / 60000;
                                if (zone == 0 || zone == 1) {
                                    System.out.println(String.format("%02d:%02d ore azi in zona speciala <%d>", hours, minutes, zone));
                                }
                                else {
                                    System.out.println(String.format("%02d:%02d ore azi in zona <%d>", hours, minutes, zone));
                                }
                            }
                            System.exit(0);
                        }

                    }
                }
                else {
                    System.out.println("Comanda inexistenta!");
                    System.out.println("Pentru a obtine lista cu toate comenzile disponibile, puteti folosi comanda {listCommands}.");
                }
        }
    }

    private static void saveTimeSpentInZones(int studentId, Map<Integer, Long> timeSpentInZones) {
        try (Connection conn = DatabaseConnection.getConnection()) {
            LocalDate today = LocalDate.now();
            LocalDate lastUpdateDate = getLastUpdateDate(conn, studentId);
            for (Map.Entry<Integer, Long> entry : timeSpentInZones.entrySet()) {
                int zone = entry.getKey();
                long timeSpent = entry.getValue();
                if (zone == 0 || zone == 1) {
                    String timeSpentInterval = convertMillisecondsToHHMM(timeSpent);
                    String sql = "UPDATE students SET " +
                            "hours_studied_today = CASE " +
                            "  WHEN ? <> ?::date THEN ?::interval " +
                            "  ELSE hours_studied_today + ?::interval " +
                            "END, " +
                            "hours_studied_semester = hours_studied_semester + ?::interval " +
                            "WHERE student_id = ?";
                    try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                        pstmt.setDate(1, java.sql.Date.valueOf(lastUpdateDate));
                        pstmt.setString(2, today.toString());
                        pstmt.setString(3, timeSpentInterval);
                        pstmt.setString(4, timeSpentInterval);
                        pstmt.setString(5, timeSpentInterval);
                        pstmt.setInt(6, studentId);
                        int rowsUpdated = pstmt.executeUpdate();
                        updateLastUpdateDate(conn, studentId, today);
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                    updateZoneHours(conn, studentId, zone, timeSpentInterval);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static void updateZoneHours(Connection conn, int studentId, int zone, String timeSpentInterval) {
        try {
            String query = "SELECT hours_studied_semester FROM zone_hours WHERE student_id = ? AND zone = ?";
            PreparedStatement stmt = conn.prepareStatement(query);
            stmt.setInt(1, studentId);
            stmt.setInt(2, zone);
            ResultSet rs = stmt.executeQuery();
            String currentInterval = "00:00";
            if (rs.next()) {
                currentInterval = rs.getString("hours_studied_semester");
            }
            rs.close();
            stmt.close();
            String updateQuery = "UPDATE zone_hours SET hours_studied_semester = ?::interval + ?::interval WHERE " +
                    "student_id = ? AND zone = ?";
            PreparedStatement updateStmt = conn.prepareStatement(updateQuery);
            updateStmt.setString(1, currentInterval);
            updateStmt.setString(2, timeSpentInterval);
            updateStmt.setInt(3, studentId);
            updateStmt.setInt(4, zone);
            int rowsUpdated = updateStmt.executeUpdate();
            updateStmt.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static LocalDate getLastUpdateDate(Connection conn, int studentId) throws SQLException {
        LocalDate lastUpdateDate = LocalDate.now();
        String sql = "SELECT last_update_date FROM students WHERE student_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, studentId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                java.sql.Date sqlDate = rs.getDate("last_update_date");
                if (sqlDate != null) {
                    lastUpdateDate = sqlDate.toLocalDate();
                }
            }
        }
        return lastUpdateDate;
    }

    private static void updateLastUpdateDate(Connection conn, int studentId, LocalDate date) throws SQLException {
        String sql = "UPDATE students SET last_update_date = ? WHERE student_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setDate(1, java.sql.Date.valueOf(date));
            pstmt.setInt(2, studentId);
            int rowsUpdated = pstmt.executeUpdate();
        }
    }

    private static String convertMillisecondsToHHMM(long milliseconds) {
        long seconds = milliseconds / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        return String.format("%02d:%02d", hours, minutes);
    }

    private static boolean hasMetRequiredHours(int studentId, int zone) {
        try (Connection conn = DatabaseConnection.getConnection()) {
            String query = "SELECT hours_studied_semester FROM zone_hours WHERE student_id = ? AND zone = ?";
            PreparedStatement stmt = conn.prepareStatement(query);
            stmt.setInt(1, studentId);
            stmt.setInt(2, zone);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                String hoursStudiedInterval = rs.getString("hours_studied_semester");
                int requiredHours = requiredHoursPerZone.getOrDefault(zone, Integer.MAX_VALUE);
                String[] timeParts = hoursStudiedInterval.split(":");
                int hoursStudied = Integer.parseInt(timeParts[0]);
                int minutesStudied = Integer.parseInt(timeParts[1]);
                int totalHoursStudied = hoursStudied + minutesStudied / 60;
                rs.close();
                stmt.close();
                return totalHoursStudied >= requiredHours;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            System.out.println("Error querying database.");
        }
        return false;
    }

    private static void resetHoursStudiedToday(Connection conn, int studentId) throws SQLException {
        String sql = "UPDATE students SET hours_studied_today = CAST(? AS INTERVAL) WHERE student_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, "0 hours 0 minutes 0 seconds");
            pstmt.setInt(2, studentId);
            int rowsUpdated = pstmt.executeUpdate();
        }
    }

    private static int getHoursStudiedForZone(int studentId, int zone) {
        int hoursStudied = 0;
        try (Connection conn = DatabaseConnection.getConnection()) {
            String query = "SELECT hours_studied_semester FROM zone_hours WHERE student_id = ? AND zone = ?";
            PreparedStatement stmt = conn.prepareStatement(query);
            stmt.setInt(1, studentId);
            stmt.setInt(2, zone);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                String hoursStudiedInterval = rs.getString("hours_studied_semester");
                String[] timeParts = hoursStudiedInterval.split(":");
                int hours = Integer.parseInt(timeParts[0]);
                int minutes = Integer.parseInt(timeParts[1]);
                hoursStudied = hours + (minutes / 60);
            }
            rs.close();
            stmt.close();
        } catch (SQLException e) {
            e.printStackTrace();
            System.out.println("Error querying database.");
        }
        return hoursStudied;
    }

    private static String getStudentName(int studentId) {
        String studentName = null;
        try (Connection conn = DatabaseConnection.getConnection()) {
            String query = "SELECT student_name FROM Students WHERE student_id = ?";
            PreparedStatement stmt = conn.prepareStatement(query);
            stmt.setInt(1, studentId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                studentName = rs.getString("student_name");
            }
        } catch (SQLException e) {
            e.printStackTrace();
            System.out.println("Error querying database.");
        }
        return studentName;
    }

    private static boolean isWithinSchedule(int studentId, int zone) {
        LocalDate currentDate = LocalDate.now();
        LocalTime currentTime = LocalTime.now();
        try (Connection conn = DatabaseConnection.getConnection()) {
            String query = "SELECT day_of_week, start_time, end_time FROM student_schedule WHERE student_id = ? AND zone = ?";
            PreparedStatement stmt = conn.prepareStatement(query);
            stmt.setInt(1, studentId);
            stmt.setInt(2, zone);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                String day_of_week = rs.getString("day_of_week");
                LocalTime startTime = rs.getTime("start_time").toLocalTime();
                LocalTime endTime = rs.getTime("end_time").toLocalTime();
                if (day_of_week.equalsIgnoreCase(currentDate.getDayOfWeek().toString()) &&
                        !currentTime.isBefore(startTime) &&
                        !currentTime.isAfter(endTime)) {
                    return true;
                }
            }
            rs.close();
            stmt.close();
        } catch (SQLException e) {
            e.printStackTrace();
            System.out.println("Error querying the database.");
        }
        return false;
    }

    private static Apdu processLine(CadClientInterface cad, String line) throws IOException {
        var res = line.replace(";", "").split(" ");
        var bytes = new byte[res.length];
        for (int i = 0; i < res.length; i++) {
            bytes[i] = (byte) Integer.parseInt(res[i].substring(2), 16);
        }
        Apdu apdu = new Apdu();
        apdu.command = new byte[]{bytes[0], bytes[1], bytes[2], bytes[3]};
        byte[] data = new byte[bytes.length - 6];
        System.arraycopy(bytes, 5, data, 0, bytes.length - 1 - 5);
        apdu.setDataIn(data, bytes[4]);
        apdu.setLe(bytes[bytes.length - 1]);
        try {
            cad.exchangeApdu(apdu);
        } catch (CadTransportException e) {
            throw new RuntimeException(e);
        }
        var dataOut = apdu.getDataOut();
        var status = apdu.getSw1Sw2();
        var toString = apdu.toString();
        return apdu;
    }

    public static String charToIntBase16(char c) {
        int value = Character.getNumericValue(c);
        return Integer.toHexString(value).toUpperCase();
    }

    private static String byteArrayToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    public static String hexBytesToDecimalString(byte[] hexBytes) {
        StringBuilder decimalStringBuilder = new StringBuilder();
        for (byte b : hexBytes) {
            int unsignedDecimalValue = b & 0xFF;
            decimalStringBuilder.append(unsignedDecimalValue).append(" ");
        }
        if (decimalStringBuilder.length() > 0) {
            decimalStringBuilder.setLength(decimalStringBuilder.length() - 1);
        }
        return decimalStringBuilder.toString();
    }

    public static void main(String[] args) {
        Terminal conn = new Terminal();
        conn.open_process();
        conn.connection();
    }
}
