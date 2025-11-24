import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.FontUIResource;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.Random;

// ===================================================================================
// 1. CORE DATA CLASSES (User, Database, DAO)
// ===================================================================================

class User {
    private String username;
    private String password;
    private double balance;
    private Map<String, Integer> portfolio;

    public User(String username, String password, double balance) {
        this.username = username;
        this.password = password;
        this.balance = balance;
        this.portfolio = new HashMap<>();
    }

    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public double getBalance() { return balance; }
    public void setBalance(double balance) {
        this.balance = balance;
        try { UserDAO.getInstance().update(this); } catch (Exception e) { e.printStackTrace(); }
    }

    public Map<String, Integer> getPortfolio() { return portfolio; }
    public void setPortfolio(Map<String, Integer> portfolio) { this.portfolio = portfolio; }

    @Override
    public String toString() { return "User: " + username + " (Balance: " + balance + ")"; }
}

interface DataAccessor<T> {
    T read(String key) throws SQLException;
    T update(T entity) throws SQLException;
    List<T> findAll() throws SQLException;
}

class DatabaseUtil {
    // UPDATE YOUR DATABASE CREDENTIALS HERE
    private static final String DB_URL = "jdbc:mysql://sql12.freesqldatabase.com:3306/sql12808979";
    private static final String DB_USER = "sql12808979";
    private static final String DB_PASS = "9eWhHHctzE";

    private static final String CREATE_USERS_TABLE = "CREATE TABLE IF NOT EXISTS users ("
            + "username VARCHAR(50) PRIMARY KEY,"
            + "password VARCHAR(100) NOT NULL,"
            + "balance DOUBLE NOT NULL"
            + ")";

    private static final String CREATE_TRANSACTIONS_TABLE = "CREATE TABLE IF NOT EXISTS transactions ("
            + "id INT AUTO_INCREMENT PRIMARY KEY,"
            + "sender VARCHAR(50) NOT NULL,"
            + "recipient VARCHAR(50) NOT NULL,"
            + "amount DOUBLE NOT NULL,"
            + "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
            + ")";

    public static Connection getConnection() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
        } catch (ClassNotFoundException e) {
            throw new SQLException("MySQL Driver not found. Ensure JDBC connector is in classpath or you are offline.");
        }
    }

    public static void initializeDatabase() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_USERS_TABLE);
            stmt.execute(CREATE_TRANSACTIONS_TABLE);
            System.out.println("Database tables initialized successfully.");
        } catch (SQLException e) {
            System.err.println("Database initialization failed: " + e.getMessage());
        }
    }

    public static void transferFunds(String senderUsername, String recipientUsername, double amount) throws SQLException {
        Connection conn = null;
        try {
            conn = getConnection();
            conn.setAutoCommit(false);

            String withdrawSQL = "UPDATE users SET balance = balance - ? WHERE username = ? AND balance >= ?";
            try (PreparedStatement withdrawStmt = conn.prepareStatement(withdrawSQL)) {
                withdrawStmt.setDouble(1, amount);
                withdrawStmt.setString(2, senderUsername);
                withdrawStmt.setDouble(3, amount);
                if (withdrawStmt.executeUpdate() != 1) {
                    throw new SQLException("Sender update failed (Insufficient balance or user not found).");
                }
            }

            String depositSQL = "UPDATE users SET balance = balance + ? WHERE username = ?";
            try (PreparedStatement depositStmt = conn.prepareStatement(depositSQL)) {
                depositStmt.setDouble(1, amount);
                depositStmt.setString(2, recipientUsername);
                if (depositStmt.executeUpdate() != 1) {
                    throw new SQLException("Recipient update failed (User not found).");
                }
            }

            String logSQL = "INSERT INTO transactions (sender, recipient, amount) VALUES (?, ?, ?)";
            try (PreparedStatement logStmt = conn.prepareStatement(logSQL)) {
                logStmt.setString(1, senderUsername);
                logStmt.setString(2, recipientUsername);
                logStmt.setDouble(3, amount);
                logStmt.executeUpdate();
            }

            conn.commit();

        } catch (SQLException e) {
            if (conn != null) conn.rollback();
            throw new SQLException("Transaction failed and was rolled back: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.setAutoCommit(true);
                conn.close();
            }
        }
    }
}

class UserDAO implements DataAccessor<User> {
    private static UserDAO instance;
    private UserDAO() {}

    public static UserDAO getInstance() {
        if (instance == null) instance = new UserDAO();
        return instance;
    }

    public User create(User user) throws SQLException {
        String sql = "INSERT INTO users (username, password, balance) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, user.getUsername());
            stmt.setString(2, user.getPassword());
            stmt.setDouble(3, user.getBalance());
            stmt.executeUpdate();
            return user;
        }
    }

    @Override
    public User read(String username) throws SQLException {
        String sql = "SELECT * FROM users WHERE username = ?";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new User(rs.getString("username"), rs.getString("password"), rs.getDouble("balance"));
                }
            }
        }
        return null;
    }

    @Override
    public User update(User user) throws SQLException {
        String sql = "UPDATE users SET password = ?, balance = ? WHERE username = ?";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, user.getPassword());
            stmt.setDouble(2, user.getBalance());
            stmt.setString(3, user.getUsername());
            if (stmt.executeUpdate() == 0) {
                throw new SQLException("User not found for update: " + user.getUsername());
            }
            return user;
        }
    }

    @Override
    public List<User> findAll() throws SQLException {
        List<User> userList = new ArrayList<>();
        String sql = "SELECT * FROM users";
        try (Connection conn = DatabaseUtil.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                userList.add(new User(rs.getString("username"), rs.getString("password"), rs.getDouble("balance")));
            }
        }
        return userList;
    }
}

// ===================================================================================
// 2. THEME MANAGER (NEW)
// ===================================================================================

class ThemeManager {
    public enum ColorScheme {
        DARK(new Color(20, 22, 28), Color.WHITE, new Color(60, 68, 79)),
        LIGHT(new Color(245, 245, 245), Color.BLACK, new Color(200, 200, 200)),
        CYBER(new Color(10, 10, 10), new Color(0, 255, 0), new Color(20, 40, 20)),
        OCEAN(new Color(10, 30, 60), new Color(200, 230, 255), new Color(30, 60, 90));

        final Color bg, fg, accent;
        ColorScheme(Color bg, Color fg, Color accent) {
            this.bg = bg; this.fg = fg; this.accent = accent;
        }
    }

    public static ColorScheme currentScheme = ColorScheme.DARK;
    public static float fontScale = 1.0f;

    public static void applyThemeToAll() {
        for (Window window : Window.getWindows()) {
            updateComponentRecursively(window);
            SwingUtilities.updateComponentTreeUI(window);
            window.validate();
            window.repaint();
        }
    }

    private static void updateComponentRecursively(Component c) {
        if (c instanceof JPanel) {
            // Don't overwrite custom drawn panels like stock graph or game
            if (!(c instanceof StockGraphPanel) && !(c instanceof GamePanel)) {
                c.setBackground(currentScheme.bg);
            }
        }

        if (c instanceof JComponent) {
            JComponent jc = (JComponent) c;
            // Handle specific components
            if (c instanceof JTextArea || c instanceof JTextField || c instanceof JList) {
                c.setBackground(currentScheme == ColorScheme.LIGHT ? Color.WHITE : currentScheme.accent);
                c.setForeground(currentScheme.fg);
            } else if (c instanceof JButton) {
                c.setBackground(currentScheme.accent);
                c.setForeground(currentScheme.fg);
            } else if (c instanceof JLabel || c instanceof JCheckBox || c instanceof JRadioButton) {
                c.setForeground(currentScheme.fg);
            }

            // Recursively update borders if they are TitledBorders
            if (jc.getBorder() instanceof TitledBorder) {
                ((TitledBorder) jc.getBorder()).setTitleColor(currentScheme.fg);
            }
        }

        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                updateComponentRecursively(child);
            }
        }
    }

    public static void setFontScale(float scale) {
        fontScale = scale;
        Enumeration<Object> keys = UIManager.getDefaults().keys();
        while (keys.hasMoreElements()) {
            Object key = keys.nextElement();
            Object value = UIManager.get(key);
            if (value instanceof FontUIResource) {
                FontUIResource orig = (FontUIResource) value;
                Font font = new Font(orig.getName(), orig.getStyle(), (int)(orig.getSize() * scale));
                UIManager.put(key, new FontUIResource(font));
            }
        }
        applyThemeToAll();
    }
}


// ===================================================================================
// 3. MAIN CLASS AND DASHBOARD
// ===================================================================================

public class JavaGuiOs{
    private static final Scanner sc = new Scanner(System.in);
    private static User currentUser;
    private static final Map<String, User> activeUsers = new HashMap<>();
    private static final UserDAO userDAO = UserDAO.getInstance();

    public static void main(String[] args) {
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║    WELCOME TO GUI OS - JDBC Ultimate Version 6.1      ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");

        DatabaseUtil.initializeDatabase();

        login();

        if (currentUser != null) {
            SwingUtilities.invokeLater(() -> new OS_Dashboard(currentUser, activeUsers));
        }
    }

    private static void login() {
        System.out.println("\nPlease log in via the console to launch the GUI.");
        System.out.print("Username: ");
        String username = sc.nextLine();
        System.out.print("Password: ");
        String password = sc.nextLine();

        try {
            User existingUser = userDAO.read(username);
            if (existingUser != null && existingUser.getPassword().equals(password)) {
                currentUser = existingUser;
                System.out.println("\nWelcome back, " + currentUser.getUsername() + "! Launching GUI...");
            } else if (existingUser == null) {
                System.out.println("User not found. Creating a new account in the database...");
                currentUser = new User(username, password, 10000.0);
                userDAO.create(currentUser);
                System.out.println("\nWelcome, " + currentUser.getUsername() + "! Account created. Launching GUI...");
            } else {
                System.out.println("Password incorrect. Please try again.");
                currentUser = null;
                return;
            }
            activeUsers.put(username, currentUser);
        } catch (SQLException e) {
            System.err.println("Database error during login: " + e.getMessage());
            currentUser = null;
        }
    }
}

class OS_Dashboard extends JFrame {
    private final User currentUser;
    private final Map<String, User> users;

    public OS_Dashboard(User currentUser, Map<String, User> users) {
        this.currentUser = currentUser;
        this.users = users;

        setTitle("GUI OS Dashboard - Welcome " + currentUser.getUsername());
        setSize(1000, 720);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        // Initial Theme Application
        ThemeManager.applyThemeToAll();

        JPanel mainPanel = new JPanel(new GridLayout(2, 5, 18, 18));
        mainPanel.setBorder(new EmptyBorder(18, 18, 18, 18));

        // Dashboard specific styling handled by ThemeManager now,
        // but we set initial generic background just in case
        mainPanel.setBackground(new Color(20, 22, 28));

        JButton btnCalculator = createAppButton("Calculator", "A simple calculator.");
        JButton btnGames = createAppButton("Games Arcade", "Play visually improved games.");
        JButton btnUtilities = createAppButton("Utilities", "Handy utility functions.");
        JButton btnChat = createAppButton("Chat System", "Peer-to-peer chat.");
        JButton btnPayment = createAppButton("Digital Payment", "Transfer money.");
        JButton btnStock = createAppButton("Stock Market", "Stock simulation with live graphs.");
        JButton btnNotes = createAppButton("Notes", "Create and manage your notes.");
        JButton btnHowItWorks = createAppButton("How It Works", "Learn about the OS architecture.");
        JButton btnSettings = createAppButton("Settings", "Change application preferences.");
        JButton btnExit = createAppButton("Exit", "Close the operating system.");
        btnExit.setBackground(new Color(255, 80, 80));

        btnCalculator.addActionListener(e -> new CalculatorGUI());
        btnGames.addActionListener(e -> new GamesGUI(this));
        btnNotes.addActionListener(e -> new NotesGUI(currentUser));
        btnChat.addActionListener(e -> new ChatSystemGUI(currentUser));
        btnPayment.addActionListener(e -> new PaymentSystemGUI(currentUser));
        btnStock.addActionListener(e -> new StockMarketGUI(currentUser));
        btnUtilities.addActionListener(e -> new UtilitiesGUI());
        btnHowItWorks.addActionListener(e -> new HowItWorksGUI(this));
        btnSettings.addActionListener(e -> new SettingsGUI(this));
        btnExit.addActionListener(e -> System.exit(0));

        mainPanel.add(btnCalculator);
        mainPanel.add(btnGames);
        mainPanel.add(btnUtilities);
        mainPanel.add(btnChat);
        mainPanel.add(btnPayment);
        mainPanel.add(btnStock);
        mainPanel.add(btnNotes);
        mainPanel.add(btnHowItWorks);
        mainPanel.add(btnSettings);
        mainPanel.add(btnExit);

        add(mainPanel);
        setVisible(true);

        // Ensure theme applies immediately
        ThemeManager.applyThemeToAll();
    }

    private JButton createAppButton(String title, String tooltip) {
        JButton button = new JButton(title);
        button.setToolTipText(tooltip);
        button.setFont(new Font("Segoe UI", Font.BOLD, 18));
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(90, 100, 110)), BorderFactory.createEmptyBorder(12, 12, 12, 12)));
        return button;
    }
}

// ===================================================================================
// 4. IMPROVED STOCK MARKET
// ===================================================================================

class StockMarketGUI extends JFrame {
    private final User currentUser;
    private final Map<String, Stock> stocks = new LinkedHashMap<>();
    private final JTextArea portfolioArea;
    private final JLabel balanceLabel;
    private final Random random = new Random();
    private final DecimalFormat moneyFmt = new DecimalFormat("#,##0.00");

    // Controls
    private JCheckBox showMABox;
    private JComboBox<String> timeframeCombo;
    private JButton pauseBtn, exportBtn;
    private JButton buyFromGraphBtn, sellFromGraphBtn;
    private StockGraphPanel graphPanel;
    private JComboBox<String> symbolSelector;

    private javax.swing.Timer feedTimer;
    private boolean feedRunning = true;

    public StockMarketGUI(User user) {
        this.currentUser = user;
        stocks.put("APPLE", new Stock("APPL", "Apple Inc.", 150.0));
        stocks.put("GOOGLE", new Stock("GOOGLE", "Alphabet Inc.", 2700.0));
        stocks.put("MSFT", new Stock("MSFT", "Microsoft Corp", 300.0));
        stocks.put("TESLA", new Stock("TESLA", "Tesla Inc", 200.0));
        stocks.put("AMAZON", new Stock("AMAZON", "Amazon.com Inc", 180.0));

        setTitle("Stock Market - " + currentUser.getUsername());
        setSize(1100, 720);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(12, 12));

        balanceLabel = new JLabel("Cash: ₹" + moneyFmt.format(currentUser.getBalance()));
        balanceLabel.setFont(new Font("Segoe UI", Font.BOLD, 20));
        balanceLabel.setForeground(new Color(30, 120, 30));
        JPanel balancePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        balancePanel.setBorder(BorderFactory.createTitledBorder("Account Balance"));
        balancePanel.add(balanceLabel);

        JPanel leftPanel = new JPanel(new BorderLayout(8, 8));
        JPanel stockListPanel = new JPanel();
        stockListPanel.setLayout(new BoxLayout(stockListPanel, BoxLayout.Y_AXIS));
        stockListPanel.setBorder(BorderFactory.createTitledBorder("Available Stocks"));
        for (Stock stock : stocks.values()) {
            stockListPanel.add(createStockRowPanel(stock));
        }
        leftPanel.add(new JScrollPane(stockListPanel), BorderLayout.CENTER);
        leftPanel.setPreferredSize(new Dimension(380, 0));

        JPanel rightPanel = new JPanel(new BorderLayout(8, 8));
        rightPanel.setBorder(new EmptyBorder(8, 8, 8, 8));

        Stock first = stocks.values().iterator().next();
        graphPanel = new StockGraphPanel(first.getPriceHistory(), first.symbol);
        graphPanel.setPreferredSize(new Dimension(700, 420));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        showMABox = new JCheckBox("Show 10-period MA");
        timeframeCombo = new JComboBox<>(new String[]{"Last 10", "Last 20", "Last 50", "Full"});
        timeframeCombo.setSelectedItem("Last 20");
        pauseBtn = new JButton("Pause");
        exportBtn = new JButton("Export PNG");
        buyFromGraphBtn = new JButton("Buy Selected");
        sellFromGraphBtn = new JButton("Sell Selected");

        controls.add(new JLabel("Symbol:"));
        symbolSelector = new JComboBox<>(stocks.keySet().toArray(new String[0]));
        controls.add(symbolSelector);
        controls.add(showMABox);
        controls.add(new JLabel("Range:"));
        controls.add(timeframeCombo);
        controls.add(pauseBtn);
        controls.add(exportBtn);
        controls.add(buyFromGraphBtn);
        controls.add(sellFromGraphBtn);

        rightPanel.add(controls, BorderLayout.NORTH);
        rightPanel.add(graphPanel, BorderLayout.CENTER);

        portfolioArea = new JTextArea();
        portfolioArea.setEditable(false);
        portfolioArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane portfolioScrollPane = new JScrollPane(portfolioArea);
        portfolioScrollPane.setBorder(BorderFactory.createTitledBorder("My Portfolio"));
        portfolioScrollPane.setPreferredSize(new Dimension(0, 160));

        add(balancePanel, BorderLayout.NORTH);
        add(leftPanel, BorderLayout.WEST);
        add(rightPanel, BorderLayout.CENTER);
        add(portfolioScrollPane, BorderLayout.SOUTH);

        symbolSelector.addActionListener(e -> {
            String sym = (String) symbolSelector.getSelectedItem();
            if (sym != null) {
                graphPanel.setSymbol(sym);
                graphPanel.setPriceHistory(stocks.get(sym).getPriceHistory());
            }
        });

        showMABox.addActionListener(e -> graphPanel.setShowMA(showMABox.isSelected()));
        timeframeCombo.addActionListener(e -> graphPanel.setRange(timeframeCombo.getSelectedItem().toString()));

        pauseBtn.addActionListener(e -> toggleFeed());
        exportBtn.addActionListener(e -> exportGraphImage());

        buyFromGraphBtn.addActionListener(e -> buySelectedFromGraph());
        sellFromGraphBtn.addActionListener(e -> sellSelectedFromGraph());

        feedTimer = new javax.swing.Timer(1200, e -> {
            if (!feedRunning) return;
            updatePricesStep();
        });
        feedTimer.start();

        updatePortfolioDisplay();
        ThemeManager.applyThemeToAll(); // Apply theme when opened
        setVisible(true);
    }

    private void toggleFeed() {
        feedRunning = !feedRunning;
        pauseBtn.setText(feedRunning ? "Pause" : "Resume");
    }

    private void exportGraphImage() {
        BufferedImage img = new BufferedImage(graphPanel.getWidth(), graphPanel.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        graphPanel.paint(g);
        g.dispose();
        try {
            File out = new File("stock_graph_" + graphPanel.getSymbol() + ".png");
            ImageIO.write(img, "png", out);
            JOptionPane.showMessageDialog(this, "Graph exported to: " + out.getAbsolutePath());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Failed to export image: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private JPanel createStockRowPanel(Stock stock) {
        JPanel stockRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        stockRow.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(200,200,200,50)), BorderFactory.createEmptyBorder(6,6,6,6)));
        JLabel infoLabel = new JLabel();
        updateStockLabel(infoLabel, stock);
        infoLabel.setPreferredSize(new Dimension(260, 28));

        JTextField qtyField = new JTextField("1", 4);
        JButton buyBtn = new JButton("Buy");
        JButton sellBtn = new JButton("Sell");
        JButton graphBtn = new JButton("View Graph");

        buyBtn.addActionListener(e -> buyStock(stock, qtyField));
        sellBtn.addActionListener(e -> sellStock(stock, qtyField));
        graphBtn.addActionListener(e -> {
            symbolSelector.setSelectedItem(stock.symbol);
            graphPanel.setSymbol(stock.symbol);
            graphPanel.setPriceHistory(stock.getPriceHistory());
        });

        stockRow.add(infoLabel);
        stockRow.add(new JLabel("Qty:"));
        stockRow.add(qtyField);
        stockRow.add(buyBtn);
        stockRow.add(sellBtn);
        stockRow.add(graphBtn);
        return stockRow;
    }

    private void updateStockLabel(JLabel label, Stock stock) {
        String trend = stock.price > stock.previousPrice ? "▲" : (stock.price < stock.previousPrice ? "▼" : "—");
        label.setText(String.format("%s %s (%s) - ₹%s", trend, stock.name, stock.symbol, moneyFmt.format(stock.price)));
        label.setForeground(stock.price > stock.previousPrice ? new Color(0, 128, 0) : (stock.price < stock.previousPrice ? Color.RED : ThemeManager.currentScheme.fg));
    }

    private void buyStock(Stock stock, JTextField qtyField) {
        try {
            int quantity = Integer.parseInt(qtyField.getText());
            if (quantity <= 0) throw new NumberFormatException();
            double totalCost = stock.price * quantity;
            if (currentUser.getBalance() < totalCost) {
                JOptionPane.showMessageDialog(this, "Insufficient funds!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            currentUser.setBalance(currentUser.getBalance() - totalCost);
            currentUser.getPortfolio().put(stock.symbol, currentUser.getPortfolio().getOrDefault(stock.symbol, 0) + quantity);
            updatePortfolioDisplay();
            balanceLabel.setText("Cash: ₹" + moneyFmt.format(currentUser.getBalance()));
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Invalid quantity!", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void sellStock(Stock stock, JTextField qtyField) {
        try {
            int quantity = Integer.parseInt(qtyField.getText());
            if (quantity <= 0) throw new NumberFormatException();
            int owned = currentUser.getPortfolio().getOrDefault(stock.symbol, 0);
            if (owned < quantity) {
                JOptionPane.showMessageDialog(this, "You don't own enough shares!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            currentUser.setBalance(currentUser.getBalance() + (stock.price * quantity));
            int newQuantity = owned - quantity;
            if (newQuantity == 0) currentUser.getPortfolio().remove(stock.symbol);
            else currentUser.getPortfolio().put(stock.symbol, newQuantity);
            updatePortfolioDisplay();
            balanceLabel.setText("Cash: ₹" + moneyFmt.format(currentUser.getBalance()));
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Invalid quantity!", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void buySelectedFromGraph() {
        Stock s = stocks.get(graphPanel.getSymbol());
        if (s == null) return;
        String qtyStr = JOptionPane.showInputDialog(this, "Buy how many shares of " + s.symbol + "?", "1");
        if (qtyStr == null) return;
        try {
            int qty = Integer.parseInt(qtyStr.trim());
            buyStock(s, new JTextField(String.valueOf(qty)));
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Invalid number", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void sellSelectedFromGraph() {
        Stock s = stocks.get(graphPanel.getSymbol());
        if (s == null) return;
        String qtyStr = JOptionPane.showInputDialog(this, "Sell how many shares of " + s.symbol + "?", "1");
        if (qtyStr == null) return;
        try {
            int qty = Integer.parseInt(qtyStr.trim());
            sellStock(s, new JTextField(String.valueOf(qty)));
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Invalid number", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updatePricesStep() {
        int i = 0;
        for (Stock stock : stocks.values()) {
            stock.updatePrice(random);
            i++;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                Component west = ((BorderLayout) getContentPane().getLayout()).getLayoutComponent(BorderLayout.WEST);
                if (west instanceof JPanel) {
                    JScrollPane sp = (JScrollPane) ((JPanel) west).getComponent(0);
                    JPanel listPanel = (JPanel) sp.getViewport().getView();
                    int idx = 0;
                    for (Component comp : listPanel.getComponents()) {
                        if (comp instanceof JPanel) {
                            JPanel row = (JPanel) comp;
                            for (Component inner : row.getComponents()) {
                                if (inner instanceof JLabel) {
                                    Stock s = (Stock) stocks.values().toArray()[idx];
                                    updateStockLabel((JLabel) inner, s);
                                    break;
                                }
                            }
                            idx++;
                        }
                    }
                }
            } catch (Exception e) {
                // Fail silently if UI structure changes
            }

            graphPanel.setPriceHistory(stocks.get(graphPanel.getSymbol()).getPriceHistory());
            updatePortfolioDisplay();
            balanceLabel.setText("Cash: ₹" + moneyFmt.format(currentUser.getBalance()));
        });
    }

    private void updatePortfolioDisplay() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-10s %-10s %-15s %-15s\n", "Symbol", "Shares", "Price/Share", "Total Value"));
        sb.append("=".repeat(62)).append("\n");
        double totalValue = 0;
        for (Map.Entry<String, Integer> entry : currentUser.getPortfolio().entrySet()) {
            Stock stock = stocks.get(entry.getKey());
            if (stock == null) continue;
            int shares = entry.getValue();
            double value = shares * stock.price;
            totalValue += value;
            sb.append(String.format("%-10s %-10d ₹%-14.2f ₹%-14.2f\n", stock.symbol, shares, stock.price, value));
        }
        sb.append("=".repeat(62)).append("\n");
        sb.append(String.format("Total Portfolio Value: ₹%.2f\n", totalValue));
        portfolioArea.setText(sb.toString());
    }

    private static class Stock {
        String symbol, name;
        double price, previousPrice;
        private final List<Double> priceHistory = new ArrayList<>();
        Stock(String symbol, String name, double price) {
            this.symbol = symbol;
            this.name = name;
            this.price = price;
            this.previousPrice = price;
            this.priceHistory.add(price);
        }
        void updatePrice(Random rand) {
            this.previousPrice = this.price;
            double changePercent = (rand.nextDouble() - 0.48) * 0.03;
            this.price *= (1 + changePercent);
            if (this.price < 0.1) this.price = 0.1;
            this.priceHistory.add(this.price);
            if (this.priceHistory.size() > 500) priceHistory.remove(0);
        }
        public List<Double> getPriceHistory() { return new ArrayList<>(priceHistory); }
    }
}

class StockGraphPanel extends JPanel {
    private List<Double> priceHistory = new ArrayList<>();
    private String symbol = "AAPL";
    private static final int PADDING = 40;
    private boolean showMA = false;
    private String range = "Last 20";
    private final DecimalFormat df = new DecimalFormat("0.00");

    // Interaction state
    private Point hoverPoint = null;
    private int hoverIndex = -1;

    public StockGraphPanel(List<Double> history, String symbol) {
        this.priceHistory = new ArrayList<>(history);
        this.symbol = symbol;
        // Keep graph dark explicitly for contrast
        setBackground(new Color(18, 22, 28));

        addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                handleHover(e.getPoint());
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                handleClick(e.getPoint(), e);
            }
            @Override
            public void mouseExited(MouseEvent e) {
                hoverIndex = -1;
                hoverPoint = null;
                repaint();
            }
        });
    }

    public void setPriceHistory(List<Double> history) {
        this.priceHistory = new ArrayList<>(history);
        repaint();
    }

    public void setShowMA(boolean show) {
        this.showMA = show;
        repaint();
    }

    public void setRange(String r) {
        this.range = r;
        repaint();
    }

    public String getSymbol() { return symbol; }
    public void setSymbol(String sym) { this.symbol = sym; repaint(); }

    private List<Double> getDisplayData() {
        if (priceHistory == null || priceHistory.isEmpty()) return new ArrayList<>();
        if ("Full".equals(range)) return new ArrayList<>(priceHistory);
        int count = 20;
        if (range.startsWith("Last")) {
            try { count = Integer.parseInt(range.split(" ")[1]); } catch (Exception ignored) {}
        }
        if (priceHistory.size() <= count) return new ArrayList<>(priceHistory);
        return new ArrayList<>(priceHistory.subList(priceHistory.size() - count, priceHistory.size()));
    }

    private void handleHover(Point p) {
        List<Double> data = getDisplayData();
        if (data.size() < 2) return;

        int w = getWidth() - 2 * PADDING;
        int n = data.size();

        int idx = (int) ((double) (p.x - PADDING) / (double) w * (n - 1));
        idx = Math.max(0, Math.min(n - 1, idx));

        hoverIndex = idx;

        double minPrice = Collections.min(data);
        double maxPrice = Collections.max(data);

        hoverPoint = new Point(mapX(idx, n, getWidth()), mapY(data.get(idx), minPrice, maxPrice, getHeight()));
        repaint();
    }

    private void handleClick(Point p, MouseEvent e) {
        List<Double> data = getDisplayData();
        if (hoverIndex < 0 || hoverIndex >= data.size()) return;

        double price = data.get(hoverIndex);
        if (SwingUtilities.isLeftMouseButton(e)) {
            JOptionPane.showMessageDialog(this, "Selected price for " + symbol + ": " + df.format(price) + "\n(Use the buttons above to trade)");
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        // gradient background (Graph always keeps its own look)
        Paint old = g2.getPaint();
        g2.setPaint(new GradientPaint(0, 0, new Color(12, 16, 24), 0, h, new Color(36, 44, 56)));
        g2.fillRect(0, 0, w, h);
        g2.setPaint(old);

        List<Double> displayData = getDisplayData();

        if (displayData == null || displayData.size() < 2) {
            g2.setColor(Color.LIGHT_GRAY);
            g2.drawString("Not enough data to display a graph.", 20, 20);
            return;
        }

        double minPrice = Collections.min(displayData);
        double maxPrice = Collections.max(displayData);
        double rangeVal = maxPrice - minPrice;
        if(rangeVal == 0) rangeVal = 1.0;
        minPrice -= rangeVal * 0.1;
        maxPrice += rangeVal * 0.1;

        g2.setColor(new Color(255,255,255,40));
        for (int i = 0; i <= 4; i++) {
            int yy = PADDING + i * (h - 2 * PADDING) / 4;
            g2.drawLine(PADDING, yy, w - PADDING, yy);
        }
        for (int i = 0; i <= 8; i++) {
            int xx = PADDING + i * (w - 2 * PADDING) / 8;
            g2.drawLine(xx, PADDING, xx, h - PADDING);
        }

        g2.setStroke(new BasicStroke(2f));
        int n = displayData.size();
        int[] xs = new int[n];
        int[] ys = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = mapX(i, n, w);
            ys[i] = mapY(displayData.get(i), minPrice, maxPrice, h);
        }

        GeneralPath path = new GeneralPath();
        path.moveTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) path.lineTo(xs[i], ys[i]);

        GradientPaint gp = new GradientPaint(0, PADDING, new Color(0, 200, 255, 120), 0, h, new Color(0, 50, 100, 40));
        g2.setPaint(gp);
        GeneralPath area = new GeneralPath(path);
        area.lineTo(xs[n-1], h - PADDING);
        area.lineTo(xs[0], h - PADDING);
        area.closePath();
        g2.fill(area);

        g2.setColor(new Color(0, 180, 220));
        g2.setStroke(new BasicStroke(3f));
        g2.draw(path);

        if (showMA) {
            List<Double> ma = movingAverage(displayData, Math.max(3, Math.min(10, n/2)));
            if (!ma.isEmpty()) {
                g2.setColor(new Color(255, 220, 90));
                g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f));
                GeneralPath mapath = new GeneralPath();
                int offset = n - ma.size();
                for (int i = 0; i < ma.size(); i++) {
                    int xi = xs[i + offset];
                    int yi = mapY(ma.get(i), minPrice, maxPrice, h);
                    if (i == 0) mapath.moveTo(xi, yi); else mapath.lineTo(xi, yi);
                }
                g2.draw(mapath);
            }
        }

        g2.setColor(new Color(220, 240, 255));
        int step = Math.max(1, n/15);
        for (int i = 0; i < n; i += step) {
            g2.fillOval(xs[i]-3, ys[i]-3, 6, 6);
        }

        g2.setColor(Color.LIGHT_GRAY);
        g2.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        g2.drawString("Max: ₹" + df.format(maxPrice), 10, PADDING - 8);
        g2.drawString("Min: ₹" + df.format(minPrice), 10, h - PADDING + 16);

        if (hoverPoint != null && hoverIndex >= 0 && hoverIndex < displayData.size()) {
            g2.setColor(new Color(255,255,255,200));
            int hx = hoverPoint.x;
            int hy = hoverPoint.y;

            g2.setColor(new Color(255, 255, 255, 100));
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{5}, 0));
            g2.drawLine(hx, PADDING, hx, h - PADDING);

            g2.setColor(new Color(255, 255, 255));
            g2.fillOval(hx-5, hy-5, 10, 10);

            String text = String.format("%s  ₹%s", symbol, df.format(displayData.get(hoverIndex)));
            FontMetrics fm = g2.getFontMetrics();
            int tw = fm.stringWidth(text) + 12;
            int th = fm.getHeight() + 6;
            int tx = Math.min(hx + 10, w - tw - 10);
            int ty = Math.max(hy - th - 10, 10);

            g2.setColor(new Color(20, 20, 28, 220));
            g2.fillRoundRect(tx, ty, tw, th, 8, 8);
            g2.setColor(Color.WHITE);
            g2.drawString(text, tx + 6, ty + fm.getAscent() + 3);
        }

        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Segoe UI", Font.BOLD, 16));
        g2.drawString(symbol + " — " + range + " Range", PADDING, 18);
    }

    private int mapX(int idx, int n, int w) {
        if (n <= 1) return PADDING;
        double ratio = (double) idx / (n - 1);
        return PADDING + (int) Math.round(ratio * (w - 2 * PADDING));
    }

    private int mapY(double val, double min, double max, int h) {
        if (max - min == 0) return h / 2;
        double ratio = (val - min) / (max - min);
        return h - PADDING - (int) Math.round(ratio * (h - 2 * PADDING));
    }

    private List<Double> movingAverage(List<Double> data, int period) {
        List<Double> ma = new ArrayList<>();
        if (data.size() < period) return ma;
        double sum = 0;
        for (int i = 0; i < data.size(); i++) {
            sum += data.get(i);
            if (i >= period) sum -= data.get(i - period);
            if (i >= period - 1) ma.add(sum / period);
        }
        return ma;
    }
}

// ===================================================================================
// 5. NEW SETTINGS GUI
// ===================================================================================

class SettingsGUI extends JDialog {
    public SettingsGUI(JFrame parent) {
        super(parent, "Settings", true);
        setSize(500, 450);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout(10, 10));

        // Main Tabbed Pane for Settings
        JTabbedPane tabs = new JTabbedPane();

        // 1. Theme Settings
        tabs.addTab("Appearance", createAppearancePanel());

        // 2. Additional Settings (Font, Data)
        tabs.addTab("System & Data", createSystemPanel());

        add(tabs, BorderLayout.CENTER);

        // Button Panel
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dispose());
        btnPanel.add(closeBtn);
        add(btnPanel, BorderLayout.SOUTH);

        ThemeManager.applyThemeToAll(); // Apply current theme to this dialog
        setVisible(true);
    }

    private JPanel createAppearancePanel() {
        JPanel panel = new JPanel(new GridLayout(0, 1, 10, 10));
        panel.setBorder(new EmptyBorder(15, 15, 15, 15));

        // --- Look and Feel Section ---
        JPanel lafPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        lafPanel.setBorder(BorderFactory.createTitledBorder("Window Style (Look & Feel)"));

        // Removed "Windows" and "Windows Classic" as requested
        String[] styles = {"Metal", "Nimbus", "CDE/Motif"};
        JComboBox<String> styleCombo = new JComboBox<>(styles);
        JButton applyStyleBtn = new JButton("Apply Style");

        applyStyleBtn.addActionListener(e -> {
            String selected = (String) styleCombo.getSelectedItem();
            String className = "javax.swing.plaf.metal.MetalLookAndFeel"; // Default

            if ("Nimbus".equals(selected)) className = "javax.swing.plaf.nimbus.NimbusLookAndFeel";
            else if ("CDE/Motif".equals(selected)) className = "com.sun.java.swing.plaf.motif.MotifLookAndFeel";

            try {
                UIManager.setLookAndFeel(className);
                ThemeManager.applyThemeToAll();
                JOptionPane.showMessageDialog(this, "Window style updated!");
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });

        lafPanel.add(new JLabel("Style:"));
        lafPanel.add(styleCombo);
        lafPanel.add(applyStyleBtn);
        panel.add(lafPanel);

        // --- Color Scheme Section ---
        JPanel colorPanel = new JPanel(new GridLayout(2, 2, 10, 10));
        colorPanel.setBorder(BorderFactory.createTitledBorder("Color Scheme"));

        JButton btnDark = new JButton("Dark Mode (Default)");
        JButton btnLight = new JButton("Light Mode");
        JButton btnCyber = new JButton("Cyberpunk Green");
        JButton btnOcean = new JButton("Ocean Blue");

        btnDark.addActionListener(e -> updateScheme(ThemeManager.ColorScheme.DARK));
        btnLight.addActionListener(e -> updateScheme(ThemeManager.ColorScheme.LIGHT));
        btnCyber.addActionListener(e -> updateScheme(ThemeManager.ColorScheme.CYBER));
        btnOcean.addActionListener(e -> updateScheme(ThemeManager.ColorScheme.OCEAN));

        colorPanel.add(btnDark);
        colorPanel.add(btnLight);
        colorPanel.add(btnCyber);
        colorPanel.add(btnOcean);
        panel.add(colorPanel);

        return panel;
    }

    private JPanel createSystemPanel() {
        JPanel panel = new JPanel(new GridLayout(0, 1, 10, 10));
        panel.setBorder(new EmptyBorder(15, 15, 15, 15));

        // --- Font Settings ---
        JPanel fontPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        fontPanel.setBorder(BorderFactory.createTitledBorder("Accessibility"));

        JSlider fontSlider = new JSlider(80, 150, 100);
        fontSlider.setMajorTickSpacing(10);
        fontSlider.setPaintTicks(true);
        JButton applyFontBtn = new JButton("Set Font Scale");

        applyFontBtn.addActionListener(e -> {
            float scale = fontSlider.getValue() / 100f;
            ThemeManager.setFontScale(scale);
        });

        fontPanel.add(new JLabel("Text Size %:"));
        fontPanel.add(fontSlider);
        fontPanel.add(applyFontBtn);
        panel.add(fontPanel);

        // --- Data Settings ---
        JPanel dataPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        dataPanel.setBorder(BorderFactory.createTitledBorder("Data Management"));
        JButton clearCacheBtn = new JButton("Clear Local Cache");
        clearCacheBtn.addActionListener(e -> {
            int choice = JOptionPane.showConfirmDialog(this, "This will reset local UI preferences. Continue?");
            if(choice == JOptionPane.YES_OPTION) {
                JOptionPane.showMessageDialog(this, "Cache cleared (Simulated).");
            }
        });
        dataPanel.add(clearCacheBtn);
        panel.add(dataPanel);

        return panel;
    }

    private void updateScheme(ThemeManager.ColorScheme scheme) {
        ThemeManager.currentScheme = scheme;
        ThemeManager.applyThemeToAll();
    }
}

// ===================================================================================
// 6. UTILITIES AND OTHER APPS
// ===================================================================================

class UtilitiesGUI extends JFrame {
    public UtilitiesGUI() {
        setTitle("Utility Functions");
        setSize(600, 500);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.addTab("String Reverser", createStringReverserPanel());
        tabbedPane.addTab("Word Counter", createCharCounterPanel());
        tabbedPane.addTab("Case Converter", createCaseConverterPanel());
        tabbedPane.addTab("Palindrome Checker", createPalindromePanel());

        add(tabbedPane, BorderLayout.CENTER);
        ThemeManager.applyThemeToAll();
        setVisible(true);
    }

    private JPanel createStringReverserPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        JTextArea inputArea = new JTextArea(5, 40);
        JTextArea outputArea = new JTextArea(5, 40);
        outputArea.setEditable(false);
        JButton reverseBtn = new JButton("Reverse String");
        reverseBtn.addActionListener(e -> {
            String input = inputArea.getText();
            String reversed = new StringBuilder(input).reverse().toString();
            outputArea.setText(reversed);
        });
        panel.add(new JLabel("Enter text to reverse:"), BorderLayout.NORTH);
        panel.add(new JScrollPane(inputArea), BorderLayout.CENTER);
        JPanel btnPanel = new JPanel();
        btnPanel.add(reverseBtn);
        panel.add(btnPanel, BorderLayout.SOUTH);
        JPanel resultPanel = new JPanel(new BorderLayout());
        resultPanel.add(new JLabel("Reversed text:"), BorderLayout.NORTH);
        resultPanel.add(new JScrollPane(outputArea), BorderLayout.CENTER);
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, panel, resultPanel);
        splitPane.setDividerLocation(200);
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(splitPane, BorderLayout.CENTER);
        return wrapper;
    }

    private JPanel createCharCounterPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        JTextArea inputArea = new JTextArea(10, 40);
        JTextArea outputArea = new JTextArea(10, 40);
        outputArea.setEditable(false);
        JButton countBtn = new JButton("Count Characters");
        countBtn.addActionListener(e -> {
            String input = inputArea.getText();
            int chars = input.length();
            int words = input.trim().isEmpty() ? 0 : input.trim().split("\\s+").length;
            int lines = input.isEmpty() ? 0 : input.split("\n").length;
            outputArea.setText("Characters: " + chars + "\nWords: " + words + "\nLines: " + lines);
        });
        panel.add(new JLabel("Enter text:"), BorderLayout.NORTH);
        panel.add(new JScrollPane(inputArea), BorderLayout.CENTER);
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(countBtn, BorderLayout.NORTH);
        bottomPanel.add(new JScrollPane(outputArea), BorderLayout.CENTER);
        panel.add(bottomPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createCaseConverterPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        JTextArea inputArea = new JTextArea(5, 40);
        JTextArea outputArea = new JTextArea(5, 40);
        outputArea.setEditable(false);
        JPanel btnPanel = new JPanel();
        JButton upperBtn = new JButton("UPPERCASE");
        JButton lowerBtn = new JButton("lowercase");
        JButton titleBtn = new JButton("Title Case");
        upperBtn.addActionListener(e -> outputArea.setText(inputArea.getText().toUpperCase()));
        lowerBtn.addActionListener(e -> outputArea.setText(inputArea.getText().toLowerCase()));
        titleBtn.addActionListener(e -> {
            String[] words = inputArea.getText().split(" ");
            StringBuilder result = new StringBuilder();
            for (String word : words) {
                if (!word.isEmpty()) {
                    result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase()).append(" ");
                }
            }
            outputArea.setText(result.toString().trim());
        });
        btnPanel.add(upperBtn);
        btnPanel.add(lowerBtn);
        btnPanel.add(titleBtn);
        panel.add(new JLabel("Enter text:"), BorderLayout.NORTH);
        panel.add(new JScrollPane(inputArea), BorderLayout.CENTER);
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(btnPanel, BorderLayout.NORTH);
        bottomPanel.add(new JScrollPane(outputArea), BorderLayout.CENTER);
        panel.add(bottomPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createPalindromePanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        JTextField inputField = new JTextField(30);
        JLabel resultLabel = new JLabel("Enter a word or phrase above");
        resultLabel.setHorizontalAlignment(JLabel.CENTER);
        resultLabel.setFont(new Font("Arial", Font.BOLD, 16));
        JButton checkBtn = new JButton("Check Palindrome");
        checkBtn.addActionListener(e -> {
            String input = inputField.getText().replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
            String reversed = new StringBuilder(input).reverse().toString();
            if (input.equals(reversed) && !input.isEmpty()) {
                resultLabel.setText("✓ Yes, it's a palindrome!");
                resultLabel.setForeground(new Color(0, 150, 0));
            } else {
                resultLabel.setText("✗ No, it's not a palindrome");
                resultLabel.setForeground(Color.RED);
            }
        });
        JPanel topPanel = new JPanel();
        topPanel.add(new JLabel("Enter text: "));
        topPanel.add(inputField);
        topPanel.add(checkBtn);
        panel.add(topPanel, BorderLayout.NORTH);
        panel.add(resultLabel, BorderLayout.CENTER);
        return panel;
    }
}

class ChatSystemGUI extends JFrame {
    private final User currentUser;
    private JTextArea chatArea;
    private JTextField messageField;
    private JButton sendBtn;
    private JButton connectBtn;
    private JTextField hostField;
    private JTextField portField;
    private JRadioButton serverRadio;
    private JRadioButton clientRadio;
    private Socket socket;
    private ServerSocket serverSocket;
    private PrintWriter out;
    private BufferedReader in;
    private boolean isConnected = false;
    private String remoteUsername = "Unknown";

    public ChatSystemGUI(User user) {
        this.currentUser = user;
        setTitle("Chat System - " + currentUser.getUsername());
        setSize(600, 500);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        JPanel connectionPanel = new JPanel(new FlowLayout());
        connectionPanel.setBorder(BorderFactory.createTitledBorder("Connection"));
        serverRadio = new JRadioButton("Server", true);
        clientRadio = new JRadioButton("Client");
        ButtonGroup group = new ButtonGroup();
        group.add(serverRadio);
        group.add(clientRadio);
        hostField = new JTextField("localhost", 10);
        portField = new JTextField("5000", 5);
        connectBtn = new JButton("Connect");

        connectionPanel.add(serverRadio);
        connectionPanel.add(clientRadio);
        connectionPanel.add(new JLabel("Host:"));
        connectionPanel.add(hostField);
        connectionPanel.add(new JLabel("Port:"));
        connectionPanel.add(portField);
        connectionPanel.add(connectBtn);

        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        JScrollPane scrollPane = new JScrollPane(chatArea);

        JPanel messagePanel = new JPanel(new BorderLayout(5, 5));
        messagePanel.setBorder(new EmptyBorder(5, 5, 5, 5));
        messageField = new JTextField();
        messageField.setEnabled(false);
        sendBtn = new JButton("Send");
        sendBtn.setEnabled(false);
        messagePanel.add(messageField, BorderLayout.CENTER);
        messagePanel.add(sendBtn, BorderLayout.EAST);

        add(connectionPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(messagePanel, BorderLayout.SOUTH);

        connectBtn.addActionListener(e -> handleConnection());
        sendBtn.addActionListener(e -> sendMessage());
        messageField.addActionListener(e -> sendMessage());
        serverRadio.addActionListener(e -> hostField.setEnabled(false));
        clientRadio.addActionListener(e -> hostField.setEnabled(true));
        hostField.setEnabled(false);
        ThemeManager.applyThemeToAll();
        setVisible(true);
    }

    private void handleConnection() {
        if (!isConnected) {
            new Thread(() -> {
                try {
                    if (serverRadio.isSelected()) startServer();
                    else startClient();
                } catch (Exception ex) {
                    appendMessage("System", "Connection error: " + ex.getMessage());
                }
            }).start();
        } else {
            disconnect();
        }
    }

    private void startServer() throws IOException {
        int port = Integer.parseInt(portField.getText());
        appendMessage("System", "Starting server on port " + port + "...");
        serverSocket = new ServerSocket(port);
        appendMessage("System", "Server started. Waiting for client...");
        socket = serverSocket.accept();
        setupConnection();
    }

    private void startClient() throws IOException {
        String host = hostField.getText();
        int port = Integer.parseInt(portField.getText());
        appendMessage("System", "Connecting to " + host + ":" + port + "...");
        socket = new Socket(host, port);
        setupConnection();
    }

    private void setupConnection() throws IOException {
        out = new PrintWriter(socket.getOutputStream(), true);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out.println("USERNAME:" + currentUser.getUsername());
        SwingUtilities.invokeLater(() -> {
            isConnected = true;
            connectBtn.setText("Disconnect");
            messageField.setEnabled(true);
            sendBtn.setEnabled(true);
            serverRadio.setEnabled(false);
            clientRadio.setEnabled(false);
            hostField.setEnabled(false);
            portField.setEnabled(false);
            appendMessage("System", "Connection established!");
        });
        listenForMessages();
    }

    private void listenForMessages() {
        new Thread(() -> {
            try {
                String message;
                while ((message = in.readLine()) != null) {
                    if (message.startsWith("USERNAME:")) {
                        remoteUsername = message.substring(9);
                        appendMessage("System", "Connected with " + remoteUsername);
                    } else {
                        appendMessage(remoteUsername, message);
                    }
                }
            } catch (IOException e) {
                appendMessage("System", "Connection closed by peer.");
            } finally {
                disconnect();
            }
        }).start();
    }

    private void sendMessage() {
        if (isConnected && out != null) {
            String message = messageField.getText().trim();
            if (!message.isEmpty()) {
                out.println(message);
                appendMessage("You", message);
                messageField.setText("");
            }
        }
    }

    private void appendMessage(String sender, String message) {
        SwingUtilities.invokeLater(() -> {
            chatArea.append(String.format("[%s] %s: %s\n", new SimpleDateFormat("HH:mm:ss").format(new java.util.Date()), sender, message));
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
        });
    }

    private void disconnect() {
        try {
            if (socket != null) socket.close();
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {
            appendMessage("System", "Error during disconnect: " + e.getMessage());
        } finally {
            SwingUtilities.invokeLater(() -> {
                isConnected = false;
                connectBtn.setText("Connect");
                messageField.setEnabled(false);
                sendBtn.setEnabled(false);
                serverRadio.setEnabled(true);
                clientRadio.setEnabled(true);
                hostField.setEnabled(clientRadio.isSelected());
                portField.setEnabled(true);
                appendMessage("System", "Disconnected.");
            });
        }
    }
}

class Transaction {
    final String sender, recipient;
    final double amount;
    final Timestamp timestamp;
    Transaction(String sender, String recipient, double amount, Timestamp timestamp) {
        this.sender = sender;
        this.recipient = recipient;
        this.amount = amount;
        this.timestamp = timestamp;
    }
}

class TransactionDAO {
    public List<Transaction> findTransactionsForUser(String username) throws SQLException {
        List<Transaction> transactions = new ArrayList<>();
        String sql = "SELECT * FROM transactions WHERE sender = ? OR recipient = ? ORDER BY timestamp DESC";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            stmt.setString(2, username);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    transactions.add(new Transaction(
                            rs.getString("sender"), rs.getString("recipient"),
                            rs.getDouble("amount"), rs.getTimestamp("timestamp")));
                }
            }
        }
        return transactions;
    }
}

class PaymentSystemGUI extends JFrame {
    private final User currentUser;
    private final JLabel balanceLabel;
    private final TransactionDAO transactionDAO = new TransactionDAO();

    public PaymentSystemGUI(User user) {
        this.currentUser = user;
        setTitle("JDBC Transaction System - " + currentUser.getUsername());
        setSize(700, 600);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        balanceLabel = new JLabel(String.format("₹ %.2f", currentUser.getBalance()));
        balanceLabel.setFont(new Font("Arial", Font.BOLD, 24));
        balanceLabel.setForeground(new Color(0, 128, 0));
        JPanel balancePanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        balancePanel.setBorder(BorderFactory.createTitledBorder("Current Balance (Persisted)"));
        balancePanel.add(balanceLabel);

        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.addTab("💸 Internal Transfer (Atomic)", createSendPanel());
        tabbedPane.addTab("📜 Transaction Log (CRUD Read)", createLogPanel());

        add(balancePanel, BorderLayout.NORTH);
        add(tabbedPane, BorderLayout.CENTER);
        ThemeManager.applyThemeToAll();
        setVisible(true);
    }

    private JPanel createLogPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        JTextArea logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(logArea);
        JButton refreshBtn = new JButton("Refresh Transaction Log (READ Operation)");
        refreshBtn.addActionListener(e -> {
            new Thread(() -> {
                try {
                    List<Transaction> transactions = transactionDAO.findTransactionsForUser(currentUser.getUsername());
                    StringBuilder sb = new StringBuilder();
                    sb.append(String.format("%-20s %-15s %-15s %-10s\n", "Date/Time", "Sender", "Recipient", "Amount"));
                    sb.append("-".repeat(60)).append("\n");
                    for (Transaction t : transactions) {
                        String status = t.sender.equals(currentUser.getUsername()) ? "Sent to" : "Received from";
                        String counterparty = t.sender.equals(currentUser.getUsername()) ? t.recipient : t.sender;
                        sb.append(String.format("[%s] ", new SimpleDateFormat("HH:mm:ss").format(t.timestamp)));
                        sb.append(String.format("%-20s: ₹%-9.2f\n", status + " " + counterparty, t.amount));
                    }
                    SwingUtilities.invokeLater(() -> {
                        logArea.setText(sb.toString());
                        logArea.setCaretPosition(0);
                    });
                } catch (SQLException ex) {
                    SwingUtilities.invokeLater(() -> logArea.setText("Error reading transaction log: " + ex.getMessage()));
                }
            }).start();
        });
        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(refreshBtn, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createSendPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        JPanel formPanel = new JPanel(new GridLayout(3, 2, 10, 10));
        JTextField recipientField = new JTextField();
        JTextField amountField = new JTextField();
        JButton sendBtn = new JButton("Execute Atomic Transfer (UPDATE/INSERT)");
        formPanel.add(new JLabel("Recipient Username (Must Exist):"));
        formPanel.add(recipientField);
        formPanel.add(new JLabel("Amount (₹):"));
        formPanel.add(amountField);
        JLabel infoLabel = new JLabel("Uses DatabaseUtil.transferFunds() for guaranteed transaction atomicity.", SwingConstants.CENTER);
        infoLabel.setFont(new Font("Arial", Font.ITALIC, 12));
        panel.add(formPanel, BorderLayout.NORTH);
        panel.add(infoLabel, BorderLayout.CENTER);
        panel.add(sendBtn, BorderLayout.SOUTH);
        sendBtn.addActionListener(e -> {
            try {
                String recipient = recipientField.getText().trim();
                double amount = Double.parseDouble(amountField.getText());
                if (recipient.isEmpty() || recipient.equals(currentUser.getUsername())) throw new IllegalArgumentException("Invalid recipient username.");
                if (amount <= 0) throw new IllegalArgumentException("Amount must be positive.");
                if (currentUser.getBalance() < amount) {
                    JOptionPane.showMessageDialog(this, "Insufficient local balance!", "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                new Thread(() -> executeTransfer(recipient, amount)).start();
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Invalid amount!", "Error", JOptionPane.ERROR_MESSAGE);
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
        return panel;
    }

    private void executeTransfer(String recipientUsername, double amount) {
        try {
            DatabaseUtil.transferFunds(currentUser.getUsername(), recipientUsername, amount);
            User updatedUser = UserDAO.getInstance().read(currentUser.getUsername());
            SwingUtilities.invokeLater(() -> {
                if (updatedUser != null) {
                    currentUser.setBalance(updatedUser.getBalance());
                    balanceLabel.setText(String.format("₹ %.2f", currentUser.getBalance()));
                }
                JOptionPane.showMessageDialog(this, "Transaction completed successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);
            });
        } catch (SQLException ex) {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Transaction failed and rolled back: " + ex.getMessage(), "Transaction Error", JOptionPane.ERROR_MESSAGE));
        }
    }
}

class CalculatorGUI extends JFrame implements ActionListener {
    private final JTextField display;
    private String operator = "";
    private double firstOperand = 0;
    private boolean isNewCalculation = true;

    public CalculatorGUI() {
        setTitle("Calculator");
        setSize(400, 500);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLocationRelativeTo(null);
        display = new JTextField();
        display.setEditable(false);
        display.setFont(new Font("Arial", Font.BOLD, 32));
        display.setHorizontalAlignment(JTextField.RIGHT);
        add(display, BorderLayout.NORTH);
        JPanel panel = new JPanel(new GridLayout(4, 4, 5, 5));
        String[] buttons = {"7", "8", "9", "/", "4", "5", "6", "*", "1", "2", "3", "-", "C", "0", "=", "+"};
        for (String text : buttons) {
            JButton button = new JButton(text);
            button.setFont(new Font("Arial", Font.BOLD, 24));
            button.addActionListener(this);
            panel.add(button);
        }
        add(panel, BorderLayout.CENTER);
        ThemeManager.applyThemeToAll();
        setVisible(true);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        String command = e.getActionCommand();
        if ("0123456789".contains(command)) {
            if (isNewCalculation) {
                display.setText(command);
                isNewCalculation = false;
            } else {
                display.setText(display.getText() + command);
            }
        } else if ("+-*/".contains(command)) {
            if (!display.getText().isEmpty()) {
                firstOperand = Double.parseDouble(display.getText());
                operator = command;
                isNewCalculation = true;
            }
        } else if ("=".equals(command) && !operator.isEmpty()) {
            double secondOperand = Double.parseDouble(display.getText());
            double result = 0;
            switch (operator) {
                case "+": result = firstOperand + secondOperand; break;
                case "-": result = firstOperand - secondOperand; break;
                case "*": result = firstOperand * secondOperand; break;
                case "/": result = secondOperand != 0 ? firstOperand / secondOperand : 0; break;
            }
            display.setText(String.valueOf(result));
            operator = "";
            isNewCalculation = true;
        } else if ("C".equals(command)) {
            display.setText("");
            firstOperand = 0;
            operator = "";
            isNewCalculation = true;
        }
    }
}

class HowItWorksGUI extends JDialog {
    public HowItWorksGUI(JFrame parent) {
        super(parent, "How It Works", true);
        setSize(600, 500);
        setLocationRelativeTo(parent);
        JTextArea textArea = new JTextArea();
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setFont(new Font("Arial", Font.PLAIN, 14));
        textArea.setMargin(new Insets(10, 10, 10, 10));
        textArea.setText(
                "--- Welcome to GUI OS (JDBC Version) ---\n\n" +
                        "This program simulates a simple graphical operating system built entirely in Java Swing.\n\n" +
                        "1. Core Structure:\n" +
                        "   - The program starts in the `consolebasedos` class, which handles console login.\n" +
                        "   - **JDBC INTEGRATION**: User login is handled by `UserDAO.read(username)` against the MySQL database.\n" +
                        "   - After successful login, it launches the `OS_Dashboard`.\n\n" +
                        "2. Database Operations:\n" +
                        "   - **User Data**: User accounts and balances are permanently stored in the `users` table.\n" +
                        "   - **Digital Payment System**: This application now uses `DatabaseUtil.transferFunds()`, which performs an **ATOMIC TRANSACTION** (withdraw, deposit, log) using JDBC's `conn.setAutoCommit(false)` and `conn.commit()/conn.rollback()` to ensure data integrity.\n" +
                        "   - **OOP**: Data access follows the DAO pattern with the generic `DataAccessor` interface.\n\n" +
                        "3. Concurrency:\n" +
                        "   - The GUI runs on Java's Event Dispatch Thread (EDT). All database calls are executed in separate threads (`new Thread(...)`) to keep the UI responsive, satisfying the **Multithreading** rubric."
        );
        add(new JScrollPane(textArea));
        ThemeManager.applyThemeToAll();
        setVisible(true);
    }
}

class NotesGUI extends JFrame {
    private final User currentUser;
    private final JList<String> noteList;
    private final JTextArea noteContent;
    private final DefaultListModel<String> listModel;
    private File[] noteFiles;

    public NotesGUI(User user) {
        this.currentUser = user;
        setTitle("Notes App - " + currentUser.getUsername());
        setSize(800, 600);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLocationRelativeTo(null);
        listModel = new DefaultListModel<>();
        noteList = new JList<>(listModel);
        noteContent = new JTextArea();
        noteContent.setFont(new Font("Monospaced", Font.PLAIN, 14));
        JScrollPane listScrollPane = new JScrollPane(noteList);
        JScrollPane contentScrollPane = new JScrollPane(noteContent);
        listScrollPane.setPreferredSize(new Dimension(250, 0));
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScrollPane, contentScrollPane);
        splitPane.setDividerLocation(250);
        JPanel buttonPanel = new JPanel();
        JButton btnNew = new JButton("New");
        JButton btnSave = new JButton("Save");
        JButton btnDelete = new JButton("Delete");
        buttonPanel.add(btnNew);
        buttonPanel.add(btnSave);
        buttonPanel.add(btnDelete);
        add(splitPane, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);
        noteList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) displaySelectedNote();
        });
        btnNew.addActionListener(e -> {
            noteContent.setText("");
            noteList.clearSelection();
        });
        btnSave.addActionListener(e -> saveNote());
        btnDelete.addActionListener(e -> deleteSelectedNote());
        loadNoteList();
        ThemeManager.applyThemeToAll();
        setVisible(true);
    }

    private void loadNoteList() {
        listModel.clear();
        noteFiles = NotesApp.getUserNotes(currentUser);
        if (noteFiles != null) {
            for (File file : noteFiles) {
                String title = file.getName().replace(currentUser.getUsername() + "_", "").replace(".txt", "");
                listModel.addElement(title.replaceAll("_", " "));
            }
        }
    }

    private void displaySelectedNote() {
        int selectedIndex = noteList.getSelectedIndex();
        if (selectedIndex != -1 && selectedIndex < noteFiles.length) {
            try {
                String content = new String(Files.readAllBytes(noteFiles[selectedIndex].toPath()));
                noteContent.setText(content);
                noteContent.setCaretPosition(0);
            } catch (IOException e) {
                noteContent.setText("Error reading file: " + e.getMessage());
            }
        }
    }

    private void saveNote() {
        int selectedIndex = noteList.getSelectedIndex();
        String currentTitle = (selectedIndex != -1) ? listModel.getElementAt(selectedIndex) : "";
        String title = JOptionPane.showInputDialog(this, "Enter note title:", currentTitle);
        if (title == null || title.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Title cannot be empty.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (selectedIndex != -1 && !title.equals(currentTitle)) {
            noteFiles[selectedIndex].delete();
        }
        NotesApp.createNoteWithContent(currentUser, title, noteContent.getText());
        JOptionPane.showMessageDialog(this, "Note saved successfully!");
        loadNoteList();
    }

    private void deleteSelectedNote() {
        int selectedIndex = noteList.getSelectedIndex();
        if (selectedIndex != -1) {
            if (JOptionPane.showConfirmDialog(this, "Are you sure?", "Confirm Deletion", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                if (noteFiles[selectedIndex].delete()) {
                    loadNoteList();
                    noteContent.setText("");
                } else {
                    JOptionPane.showMessageDialog(this, "Failed to delete the note file.", "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        } else {
            JOptionPane.showMessageDialog(this, "Please select a note to delete.", "Warning", JOptionPane.WARNING_MESSAGE);
        }
    }
}

class NotesApp {
    private static final String NOTES_DIR = "console_os_notes/";
    static File[] getUserNotes(User user) {
        try {
            Files.createDirectories(Paths.get(NOTES_DIR));
        } catch (IOException e) {
            e.printStackTrace();
        }
        File dir = new File(NOTES_DIR);
        return dir.listFiles((d, name) -> name.startsWith(user.getUsername() + "_") && name.endsWith(".txt"));
    }
    static void createNoteWithContent(User user, String title, String content) {
        String safeTitle = title.replaceAll("[^a-zA-Z0-9.-]", "_");
        String filename = NOTES_DIR + user.getUsername() + "_" + safeTitle + ".txt";
        try (FileWriter writer = new FileWriter(filename)) {
            writer.write(content);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

// ===================================================================================
// 7. GAMES
// ===================================================================================

class GamesGUI extends JDialog {
    public GamesGUI(JFrame parent) {
        super(parent, "Arcade Menu", true);
        setSize(400, 500);
        setLocationRelativeTo(parent);
        setLayout(new GridLayout(5, 1, 15, 15));

        JPanel content = (JPanel) getContentPane();
        content.setBorder(new EmptyBorder(20, 20, 20, 20));

        // Let ThemeManager handle background unless specifically wanted dark
        // content.setBackground(new Color(40, 40, 40));

        add(createGameButton("⌨️ Typing Speed Test", new Color(100, 180, 255), e -> new TypingSpeedTestGUI()));
        add(createGameButton("🏰 Adventure Quest", new Color(255, 180, 100), e -> new AdventureQuestGUI()));
        add(createGameButton("🐍 Snake Game", new Color(100, 255, 100), e -> new GameFrame()));
        add(createGameButton("✂️ Rock Paper Scissors", new Color(255, 100, 100), e -> runRockPaperScissorsGUI(this)));
        add(createGameButton("🔢 Number Guessing", new Color(200, 100, 255), e -> runNumberGuessingGUI(this)));

        ThemeManager.applyThemeToAll();
        setVisible(true);
    }

    private JButton createGameButton(String text, Color bg, ActionListener action) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Segoe UI", Font.BOLD, 18));
        btn.setBackground(bg);
        btn.setForeground(Color.BLACK);
        btn.setFocusPainted(false);
        btn.addActionListener(action);
        return btn;
    }

    private void runNumberGuessingGUI(Component parent) {
        int target = new Random().nextInt(100) + 1;
        int attempts = 0;
        JOptionPane.showMessageDialog(parent, "I'm thinking of a number between 1 and 100!");
        while (true) {
            String guessStr = JOptionPane.showInputDialog(parent, "Your guess:");
            if (guessStr == null) break;
            try {
                int guess = Integer.parseInt(guessStr);
                attempts++;
                if (guess == target) {
                    JOptionPane.showMessageDialog(parent, "Correct! You won in " + attempts + " attempts!", "You Won!", JOptionPane.INFORMATION_MESSAGE);
                    break;
                } else if (guess < target) {
                    JOptionPane.showMessageDialog(parent, "Higher!", "Try Again", JOptionPane.WARNING_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(parent, "Lower!", "Try Again", JOptionPane.WARNING_MESSAGE);
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(parent, "Please enter a valid number.", "Invalid Input", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void runRockPaperScissorsGUI(Component parent) {
        String[] options = {"Rock 🪨", "Paper 📄", "Scissors ✂️"};
        int playerChoice = JOptionPane.showOptionDialog(parent, "Choose your move:", "Rock, Paper, Scissors", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (playerChoice == -1) return;
        int compChoice = new Random().nextInt(3);
        String result = (playerChoice == compChoice) ? "It's a tie!" : ((playerChoice == 0 && compChoice == 2) || (playerChoice == 1 && compChoice == 0) || (playerChoice == 2 && compChoice == 1)) ? "You win! 🎉" : "Computer wins! 🤖";
        JOptionPane.showMessageDialog(parent, "You chose: " + options[playerChoice] + "\nComputer chose: " + options[compChoice] + "\n\n" + result, "Result", JOptionPane.INFORMATION_MESSAGE);
    }
}

class TypingSpeedTestGUI extends JFrame {
    private final JLabel sentenceLabel = new JLabel("Click 'Start' to begin.");
    private final JTextArea typingArea = new JTextArea(5, 30);
    private final JButton startButton = new JButton("Start Test");
    private final JLabel timerLabel = new JLabel("Time: 0s");
    private final JLabel wpmLabel = new JLabel("WPM: 0");
    private final JLabel accuracyLabel = new JLabel("Acc: 100%");
    private javax.swing.Timer timer;
    private long startTime;
    private final String[] sentences = {
            "The quick brown fox jumps over the lazy dog.",
            "Java is a versatile and powerful programming language.",
            "Technology changes fast but logic remains the same.",
            "A smooth sea never made a skilled sailor."
    };

    public TypingSpeedTestGUI() {
        setTitle("Typing Speed Test");
        setSize(700, 450);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(15, 15));
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBorder(new EmptyBorder(20, 20, 10, 20));
        sentenceLabel.setFont(new Font("Serif", Font.BOLD, 20));
        sentenceLabel.setForeground(new Color(50, 50, 150));
        headerPanel.add(new JLabel("Type this sentence:"), BorderLayout.NORTH);
        headerPanel.add(sentenceLabel, BorderLayout.CENTER);
        typingArea.setEnabled(false);
        typingArea.setLineWrap(true);
        typingArea.setWrapStyleWord(true);
        typingArea.setFont(new Font("Monospaced", Font.PLAIN, 18));
        typingArea.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)
        ));
        JPanel statsPanel = new JPanel(new GridLayout(1, 3, 20, 0));
        statsPanel.setBackground(new Color(40, 44, 52));
        statsPanel.setBorder(new EmptyBorder(15, 15, 15, 15));
        styleStatLabel(timerLabel);
        styleStatLabel(wpmLabel);
        styleStatLabel(accuracyLabel);
        statsPanel.add(timerLabel);
        statsPanel.add(wpmLabel);
        statsPanel.add(accuracyLabel);
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(statsPanel, BorderLayout.CENTER);
        startButton.setFont(new Font("Segoe UI", Font.BOLD, 14));
        bottomPanel.add(startButton, BorderLayout.EAST);
        add(headerPanel, BorderLayout.NORTH);
        add(new JScrollPane(typingArea), BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);
        startButton.addActionListener(e -> startGame());
        typingArea.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { checkTyping(); }
            public void removeUpdate(DocumentEvent e) { checkTyping(); }
            public void changedUpdate(DocumentEvent e) { checkTyping(); }
        });

        ThemeManager.applyThemeToAll();
        setVisible(true);
    }

    private void styleStatLabel(JLabel lbl) {
        lbl.setForeground(Color.CYAN);
        lbl.setFont(new Font("Monospaced", Font.BOLD, 16));
        lbl.setHorizontalAlignment(SwingConstants.CENTER);
        lbl.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
    }

    private void startGame() {
        sentenceLabel.setText(sentences[new Random().nextInt(sentences.length)]);
        typingArea.setText("");
        typingArea.setEnabled(true);
        typingArea.requestFocus();
        typingArea.setBackground(Color.WHITE);
        startButton.setEnabled(false);
        startTime = System.currentTimeMillis();
        timer = new javax.swing.Timer(100, e -> updateStats());
        timer.start();
    }

    private void updateStats() {
        long elapsed = System.currentTimeMillis() - startTime;
        timerLabel.setText(String.format("Time: %.1fs", elapsed / 1000.0));
        calculateWPM(elapsed);
        calculateAccuracy();
    }

    private void checkTyping() {
        String target = sentenceLabel.getText();
        String current = typingArea.getText();
        if (!target.startsWith(current)) {
            typingArea.setBackground(new Color(255, 230, 230));
        } else {
            typingArea.setBackground(Color.WHITE);
        }
        if (current.equals(target)) {
            timer.stop();
            typingArea.setEnabled(false);
            typingArea.setBackground(new Color(230, 255, 230));
            startButton.setEnabled(true);
            startButton.setText("Play Again");
            JOptionPane.showMessageDialog(this, "Test Finished!\n" + wpmLabel.getText(), "Complete", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void calculateWPM(long elapsedMillis) {
        if (elapsedMillis == 0) return;
        double minutes = elapsedMillis / 60000.0;
        String[] wordsTyped = typingArea.getText().trim().split("\\s+");
        int numWords = wordsTyped[0].isEmpty() ? 0 : wordsTyped.length;
        int wpm = (int) (numWords / minutes);
        wpmLabel.setText("WPM: " + wpm);
    }

    private void calculateAccuracy() {
        String target = sentenceLabel.getText();
        String typed = typingArea.getText();
        int correctChars = 0;
        for (int i = 0; i < Math.min(target.length(), typed.length()); i++) {
            if (target.charAt(i) == typed.charAt(i)) correctChars++;
        }
        double accuracy = (target.isEmpty()) ? 100.0 : ((double) correctChars / target.length()) * 100;
        accuracyLabel.setText(String.format("Acc: %.1f%%", accuracy));
    }
}

class AdventureQuestGUI extends JFrame {
    private final JTextPane storyPane;
    private final JPanel choicePanel;
    private final JLabel healthLabel, goldLabel, itemsLabel;
    private int playerHealth;
    private int playerGold;
    private boolean hasSword;
    private boolean hasKey;

    public AdventureQuestGUI() {
        setTitle("Adventure Quest: The Cursed Castle");
        setSize(800, 600);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());
        JPanel statusPanel = new JPanel(new GridLayout(1, 3, 10, 10));
        statusPanel.setBorder(new EmptyBorder(15, 20, 15, 20));
        statusPanel.setBackground(new Color(240, 240, 240));
        healthLabel = createStatusLabel("❤️ Health", Color.RED);
        goldLabel = createStatusLabel("💰 Gold", new Color(200, 150, 0));
        itemsLabel = createStatusLabel("🎒 Items", Color.BLUE);
        statusPanel.add(healthLabel);
        statusPanel.add(goldLabel);
        statusPanel.add(itemsLabel);
        storyPane = new JTextPane();
        storyPane.setEditable(false);
        storyPane.setContentType("text/html");
        storyPane.setBorder(new EmptyBorder(20, 20, 20, 20));
        choicePanel = new JPanel(new GridLayout(0, 1, 10, 10));
        choicePanel.setBorder(new EmptyBorder(20, 50, 20, 50));
        choicePanel.setBackground(Color.WHITE);
        add(statusPanel, BorderLayout.NORTH);
        add(new JScrollPane(storyPane), BorderLayout.CENTER);
        add(choicePanel, BorderLayout.SOUTH);
        startGame();
        ThemeManager.applyThemeToAll();
        setVisible(true);
    }

    private JLabel createStatusLabel(String title, Color color) {
        JLabel lbl = new JLabel(title);
        lbl.setFont(new Font("Segoe UI", Font.BOLD, 16));
        lbl.setForeground(color);
        return lbl;
    }

    private void updateStatus() {
        healthLabel.setText("❤️ Health: " + playerHealth);
        goldLabel.setText("💰 Gold: " + playerGold);
        String items = (hasSword ? "⚔️ Sword " : "") + (hasKey ? "🗝️ Key" : "");
        itemsLabel.setText("🎒 Items: " + (items.isEmpty() ? "None" : items.trim()));
        if (playerHealth <= 0) {
            javax.swing.Timer t = new javax.swing.Timer(500, e -> showScene("gameOver"));
            t.setRepeats(false);
            t.start();
        }
    }

    private void setStoryText(String text) {
        String html = "<html><body style='font-family: Segoe UI, sans-serif; font-size: 14px; padding: 10px;'>"
                + text + "</body></html>";
        storyPane.setText(html);
        storyPane.setCaretPosition(0);
    }

    private void startGame() {
        playerHealth = 100;
        playerGold = 10;
        hasSword = false;
        hasKey = false;
        updateStatus();
        showScene("start");
    }

    private void showScene(String sceneId) {
        choicePanel.removeAll();
        switch (sceneId) {
            case "start":
                setStoryText("<h2>The Crossroads</h2>You stand at a crossroads. A path leads to a dense <b>Forest</b>, another to a bustling <b>Village</b>.<br><br><i>Where does your destiny lie?</i>");
                addChoice("Enter the Forest 🌲", "forest");
                addChoice("Go to the Village 🏘️", "village");
                break;
            case "forest":
                setStoryText("<h2>The Forest</h2>The forest is dark and eerie. You see a glimmer in the mud.<br>It is a <span style='color:orange'>Rusty Key</span>!<br><br>Suddenly, you hear a menacing growl.");
                if (!hasKey) {
                    hasKey = true;
                    updateStatus();
                }
                addChoice("Go deeper into the forest 👣", "deepForest");
                addChoice("Return to crossroads ↩️", "start");
                break;
            case "deepForest":
                setStoryText("<h2>Ambush!</h2>A ferocious <b>Wolf</b> leaps from the bushes! Without a weapon, you struggle to defend yourself.<br><br><span style='color:red'>You lose 30 Health.</span>");
                playerHealth -= 30;
                updateStatus();
                if (playerHealth > 0) addChoice("Flee to the village 🏃", "village");
                break;
            case "village":
                setStoryText("<h2>Oakhaven Village</h2>The village is lively. A <b>Blacksmith</b> is hammering steel, and people are whispering about the old Castle.");
                if (playerGold >= 10 && !hasSword) {
                    addChoice("Buy Sword (10 Gold) ⚔️", "buySword");
                }
                addChoice("Talk to Villagers 🗣️", "talkVillagers");
                addChoice("Go to Castle Gate 🏰", "castleGate");
                addChoice("Return to crossroads ↩️", "start");
                break;
            case "buySword":
                playerGold -= 10;
                hasSword = true;
                updateStatus();
                setStoryText("<h2>Blacksmith</h2>You bought a <b>Steel Sword</b>! It feels heavy and sharp.<br>'Good luck,' the blacksmith grunts.");
                addChoice("Back to Village square", "village");
                break;
            case "talkVillagers":
                setStoryText("<h2>Rumors</h2>'A Dragon sleeps in the castle,' a woman whispers. 'It guards the <b style='color:gold'>Sunstone</b>.'");
                addChoice("Back", "village");
                break;
            case "castleGate":
                setStoryText("<h2>The Castle</h2>The iron gate is locked tight. It looks centuries old.");
                if (hasKey) addChoice("Use Rusty Key 🗝️", "castleInside");
                addChoice("Return to Village", "village");
                break;
            case "castleInside":
                setStoryText("<h2>The Grand Hall</h2>The gate creaks open. Inside, a massive <b style='color:red'>Red Dragon</b> sleeps atop a pile of gold.<br>The Sunstone is right there!");
                addChoice("ATTACK! ⚔️", "fightDragon");
                addChoice("Sneak... 🤫", "sneak");
                break;
            case "fightDragon":
                if (hasSword) {
                    setStoryText("<h2>Victory!</h2>You charge! The dragon wakes and breathes fire, but you dodge and strike!<br>With a mighty blow, the beast falls.<br><br><b style='color:green'>You found 100 Gold!</b>");
                    playerGold += 100;
                    showScene("victory");
                } else {
                    setStoryText("<h2>Defeat</h2>You attack with bare hands? The dragon wakes up and simply eats you.<br><br><b>GAME OVER</b>");
                    playerHealth = 0;
                    updateStatus();
                }
                break;
            case "sneak":
                setStoryText("<h2>Too Loud!</h2>You step on a coin. <i>CLINK!</i><br>The Dragon wakes up and smashes you with its tail.<br><span style='color:red'>-50 Health</span>");
                playerHealth -= 50;
                updateStatus();
                if (playerHealth > 0) addChoice("Fight for your life!", "fightDragon");
                break;
            case "victory":
                updateStatus();
                setStoryText("<h1 style='color:green'>You Win!</h1>You have the Sunstone and the glory. The village is saved!");
                addChoice("Play Again", "start");
                addChoice("Exit", "exit");
                break;
            case "gameOver":
                setStoryText("<h1 style='color:red'>Game Over</h1>Your journey ends here.");
                addChoice("Try Again", "start");
                addChoice("Exit", "exit");
                break;
        }
        choicePanel.revalidate();
        choicePanel.repaint();
    }

    private void addChoice(String text, String sceneId) {
        JButton button = new JButton(text);
        button.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        button.setFocusPainted(false);
        button.setBackground(new Color(230, 240, 255));
        button.addActionListener(e -> {
            if ("start".equals(sceneId)) startGame();
            else if ("exit".equals(sceneId)) dispose();
            else showScene(sceneId);
        });
        choicePanel.add(button);
    }
}

class GameFrame extends JFrame {
    GameFrame() {
        this.add(new GamePanel());
        this.setTitle("Snake Game");
        this.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        this.setResizable(false);
        this.pack();
        this.setVisible(true);
        this.setLocationRelativeTo(null);
    }
}

class GamePanel extends JPanel implements ActionListener {
    static final int SCREEN_WIDTH = 600;
    static final int SCREEN_HEIGHT = 600;
    static final int UNIT_SIZE = 25;
    static final int GAME_UNITS = (SCREEN_WIDTH * SCREEN_HEIGHT) / UNIT_SIZE;
    static final int DELAY = 80;
    final int[] x = new int[GAME_UNITS];
    final int[] y = new int[GAME_UNITS];
    int bodyParts = 6;
    int applesEaten;
    int appleX;
    int appleY;
    char direction = 'R';
    boolean running = false;
    boolean paused = false;
    javax.swing.Timer timer;
    java.util.Random random;

    GamePanel() {
        random = new java.util.Random();
        this.setPreferredSize(new Dimension(SCREEN_WIDTH, SCREEN_HEIGHT));
        // Game panel uses specific color, not theme color
        this.setBackground(new Color(20, 20, 20));
        this.setFocusable(true);
        this.addKeyListener(new MyKeyAdapter());
        startGame();
    }

    public void startGame() {
        bodyParts = 6;
        applesEaten = 0;
        direction = 'R';
        for (int i = 0; i < bodyParts; i++) {
            x[i] = 100 - (i * UNIT_SIZE);
            y[i] = 100;
        }
        newApple();
        running = true;
        paused = false;
        if (timer == null) {
            timer = new javax.swing.Timer(DELAY, this);
            timer.start();
        } else {
            timer.restart();
        }
        repaint();
    }

    public void paintComponent(Graphics g) {
        super.paintComponent(g);
        draw(g);
    }

    public void draw(Graphics g) {
        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (running) {
            g2d.setColor(new Color(30, 30, 30));
            for (int i = 0; i < SCREEN_HEIGHT / UNIT_SIZE; i++) {
                g2d.drawLine(i * UNIT_SIZE, 0, i * UNIT_SIZE, SCREEN_HEIGHT);
                g2d.drawLine(0, i * UNIT_SIZE, SCREEN_WIDTH, i * UNIT_SIZE);
            }
            GradientPaint appleGradient = new GradientPaint(appleX, appleY, Color.RED, appleX + UNIT_SIZE, appleY + UNIT_SIZE, new Color(150, 0, 0));
            g2d.setPaint(appleGradient);
            g2d.fillOval(appleX + 2, appleY + 2, UNIT_SIZE - 4, UNIT_SIZE - 4);
            g2d.setColor(new Color(0, 200, 0));
            g2d.fillOval(appleX + 8, appleY - 2, 8, 8);
            for (int i = 0; i < bodyParts; i++) {
                if (i == 0) {
                    g2d.setColor(new Color(0, 255, 100));
                    g2d.fillRoundRect(x[i], y[i], UNIT_SIZE, UNIT_SIZE, 10, 10);
                    g2d.setColor(Color.BLACK);
                    int eyeSize = 4;
                    if(direction == 'R') {
                        g2d.fillOval(x[i] + 15, y[i] + 5, eyeSize, eyeSize);
                        g2d.fillOval(x[i] + 15, y[i] + 15, eyeSize, eyeSize);
                    } else if (direction == 'L') {
                        g2d.fillOval(x[i] + 5, y[i] + 5, eyeSize, eyeSize);
                        g2d.fillOval(x[i] + 5, y[i] + 15, eyeSize, eyeSize);
                    } else if (direction == 'U') {
                        g2d.fillOval(x[i] + 5, y[i] + 5, eyeSize, eyeSize);
                        g2d.fillOval(x[i] + 15, y[i] + 5, eyeSize, eyeSize);
                    } else {
                        g2d.fillOval(x[i] + 5, y[i] + 15, eyeSize, eyeSize);
                        g2d.fillOval(x[i] + 15, y[i] + 15, eyeSize, eyeSize);
                    }
                } else {
                    g2d.setColor(new Color(45, 180, 0));
                    g2d.fillRoundRect(x[i], y[i], UNIT_SIZE, UNIT_SIZE, 8, 8);
                }
            }
            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("Segoe UI", Font.BOLD, 20));
            g2d.drawString("Score: " + applesEaten, 20, 30);
            g2d.setFont(new Font("Segoe UI", Font.PLAIN, 14));
            g2d.setColor(Color.GRAY);
            g2d.drawString("[P] Pause", SCREEN_WIDTH - 80, 30);
            if (paused) {
                drawCenteredString(g2d, "PAUSED", SCREEN_WIDTH, SCREEN_HEIGHT, new Font("Segoe UI", Font.BOLD, 60), Color.YELLOW);
            }
        } else {
            gameOver(g);
        }
    }

    private void drawCenteredString(Graphics2D g, String text, int width, int height, Font font, Color color) {
        g.setFont(font);
        g.setColor(color);
        FontMetrics metrics = g.getFontMetrics(font);
        int x = (width - metrics.stringWidth(text)) / 2;
        int y = ((height - metrics.getHeight()) / 2) + metrics.getAscent();
        g.drawString(text, x, y);
    }

    public void newApple() {
        appleX = random.nextInt(SCREEN_WIDTH / UNIT_SIZE) * UNIT_SIZE;
        appleY = random.nextInt(SCREEN_HEIGHT / UNIT_SIZE) * UNIT_SIZE;
    }

    public void move() {
        for (int i = bodyParts; i > 0; i--) {
            x[i] = x[i - 1];
            y[i] = y[i - 1];
        }
        switch (direction) {
            case 'U': y[0] -= UNIT_SIZE; break;
            case 'D': y[0] += UNIT_SIZE; break;
            case 'L': x[0] -= UNIT_SIZE; break;
            case 'R': x[0] += UNIT_SIZE; break;
        }
    }

    public void checkApple() {
        if (x[0] == appleX && y[0] == appleY) {
            bodyParts++;
            applesEaten++;
            newApple();
        }
    }

    public void checkCollisions() {
        for (int i = bodyParts; i > 0; i--) {
            if (x[0] == x[i] && y[0] == y[i]) {
                running = false;
                break;
            }
        }
        if (x[0] < 0 || x[0] >= SCREEN_WIDTH || y[0] < 0 || y[0] >= SCREEN_HEIGHT) {
            running = false;
        }
        if (!running) {
            timer.stop();
        }
    }

    public void gameOver(Graphics g) {
        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setColor(new Color(0,0,0, 200));
        g2d.fillRect(0,0,SCREEN_WIDTH, SCREEN_HEIGHT);
        g2d.setColor(Color.RED);
        g2d.setFont(new Font("Segoe UI", Font.BOLD, 40));
        FontMetrics metrics1 = getFontMetrics(g.getFont());
        g2d.drawString("Score: " + applesEaten, (SCREEN_WIDTH - metrics1.stringWidth("Score: " + applesEaten)) / 2, g.getFont().getSize());
        g2d.setFont(new Font("Segoe UI", Font.BOLD, 75));
        FontMetrics metrics2 = getFontMetrics(g.getFont());
        g2d.drawString("Game Over", (SCREEN_WIDTH - metrics2.stringWidth("Game Over")) / 2, SCREEN_HEIGHT / 2);
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Segoe UI", Font.BOLD, 20));
        FontMetrics metrics3 = getFontMetrics(g.getFont());
        g2d.drawString("Press 'R' to Restart", (SCREEN_WIDTH - metrics3.stringWidth("Press 'R' to Restart")) / 2, SCREEN_HEIGHT / 2 + 60);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (running && !paused) {
            move();
            checkApple();
            checkCollisions();
        }
        repaint();
    }

    public class MyKeyAdapter extends KeyAdapter {
        @Override
        public void keyPressed(KeyEvent e) {
            int key = e.getKeyCode();
            if (running && !paused) {
                if (key == KeyEvent.VK_LEFT && direction != 'R') direction = 'L';
                else if (key == KeyEvent.VK_RIGHT && direction != 'L') direction = 'R';
                else if (key == KeyEvent.VK_UP && direction != 'D') direction = 'U';
                else if (key == KeyEvent.VK_DOWN && direction != 'U') direction = 'D';
            }
            if (key == KeyEvent.VK_P && running) {
                paused = !paused;
            }
            if (!running && key == KeyEvent.VK_R) {
                startGame();
            }
            if (key == KeyEvent.VK_ESCAPE) {
                Window win = SwingUtilities.getWindowAncestor(GamePanel.this);
                if(win != null) win.dispose();
            }
        }
    }
}