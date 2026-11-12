import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

public class ROS {

    // ==========================================
    // DATABASE CONNECTION (Laragon defaults)
    // ==========================================

    private static final String DB_URL =
            "jdbc:mysql://localhost:3306/restaurant_db?useSSL=false&serverTimezone=Asia/Manila";
    private static final String DB_USER = "root";
    private static final String DB_PASSWORD = ""; // Laragon default is empty

    private static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
    }

    // ==========================================
    // MENU DATA (loaded from the menu_items table)
    // ==========================================

    private int[] menuIds = new int[20];
    private String[] menuNames = new String[20];
    private double[] menuPrices = new double[20];
    private int menuCount = 0;

    // ==========================================
    // TRANSACTIONS SHOWN IN THE HISTORY TAB
    // These are loaded from the database for the
    // date picked in the History tab.
    // ==========================================

    private List<Transaction> shownTransactions = new ArrayList<>();

    // ==========================================
    // GUI COMPONENTS
    // ==========================================

    private JFrame frame;

    private JTable menuTable;
    private DefaultTableModel menuModel;

    private JTable cartTable;
    private DefaultTableModel cartModel;

    private JTable historyTable;
    private DefaultTableModel historyModel;
    private JSpinner dateSpinner;
    private JLabel historySummaryLabel;

    private JComboBox<String> menuCombo;
    private JTextField customerField;
    private JTextField quantityField;
    private JTextField paymentField;
    private JLabel totalLabel;

    private double cartTotal = 0.0;

    // ==========================================
    // MAIN METHOD
    // ==========================================

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ROS().createGUI());
    }

    public ROS() {
        loadMenuFromDatabase();
    }

    // ==========================================
    // DATABASE: MENU
    // ==========================================

    private void loadMenuFromDatabase() {

        String sql = "SELECT menu_id, item_name, price FROM menu_items ORDER BY menu_id";

        try (Connection con = getConnection();
             PreparedStatement st = con.prepareStatement(sql);
             ResultSet rs = st.executeQuery()) {

            menuCount = 0;

            while (rs.next() && menuCount < menuNames.length) {
                menuIds[menuCount] = rs.getInt("menu_id");
                menuNames[menuCount] = rs.getString("item_name");
                menuPrices[menuCount] = rs.getDouble("price");
                menuCount++;
            }

        } catch (SQLException e) {
            showDatabaseError("Could not load the menu from the database.", e);
        }
    }

    private void insertMenuItem(String name, double price) {

        String sql = "INSERT INTO menu_items (item_name, price) VALUES (?, ?)";

        try (Connection con = getConnection();
             PreparedStatement st = con.prepareStatement(sql)) {

            st.setString(1, name);
            st.setDouble(2, price);
            st.executeUpdate();

            loadMenuFromDatabase();
            refreshMenuTable();
            refreshMenuCombo();
            showMessage(name + " has been added.");

        } catch (SQLException e) {
            showDatabaseError("Could not add the menu item.", e);
        }
    }

    private void updateMenuItemPrice(int menuId, double price) {

        String sql = "UPDATE menu_items SET price = ? WHERE menu_id = ?";

        try (Connection con = getConnection();
             PreparedStatement st = con.prepareStatement(sql)) {

            st.setDouble(1, price);
            st.setInt(2, menuId);
            st.executeUpdate();

            loadMenuFromDatabase();
            refreshMenuTable();
            refreshMenuCombo();
            showMessage("Price updated successfully.");

        } catch (SQLException e) {
            showDatabaseError("Could not update the price.", e);
        }
    }

    private void deleteMenuItem(int menuId) {

        String sql = "DELETE FROM menu_items WHERE menu_id = ?";

        try (Connection con = getConnection();
             PreparedStatement st = con.prepareStatement(sql)) {

            st.setInt(1, menuId);
            st.executeUpdate();

            loadMenuFromDatabase();
            refreshMenuTable();
            refreshMenuCombo();
            showMessage("Menu item removed.");

        } catch (SQLException e) {
            showDatabaseError("Could not remove the menu item.", e);
        }
    }

    // ==========================================
    // DATABASE: SAVE A COMPLETED TRANSACTION
    // Saves one row in transactions and one row
    // per item in transaction_items. Both are
    // saved together or not at all.
    // Returns the new transaction_id, or -1 if
    // saving failed.
    // ==========================================

    private int saveTransactionToDatabase(String customerName, double total,
                                          double payment, double change) {

        String headerSql =
                "INSERT INTO transactions (customer_name, transaction_date, "
                + "total_amount, payment_amount, change_amount) VALUES (?, ?, ?, ?, ?)";

        String itemSql =
                "INSERT INTO transaction_items (transaction_id, menu_id, item_name, "
                + "unit_price, quantity, subtotal) VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection con = getConnection()) {

            con.setAutoCommit(false);

            try (PreparedStatement header =
                         con.prepareStatement(headerSql, Statement.RETURN_GENERATED_KEYS)) {

                header.setString(1, customerName);
                header.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
                header.setDouble(3, total);
                header.setDouble(4, payment);
                header.setDouble(5, change);
                header.executeUpdate();

                int newId;
                try (ResultSet keys = header.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("No transaction ID was generated.");
                    }
                    newId = keys.getInt(1);
                }

                try (PreparedStatement item = con.prepareStatement(itemSql)) {

                    for (int row = 0; row < cartModel.getRowCount(); row++) {

                        String name = cartModel.getValueAt(row, 0).toString();
                        double unitPrice =
                                Double.parseDouble(cartModel.getValueAt(row, 1).toString());
                        int quantity =
                                Integer.parseInt(cartModel.getValueAt(row, 2).toString());
                        double subtotal =
                                Double.parseDouble(cartModel.getValueAt(row, 3).toString());

                        int menuIndex = findMenuIndex(name);

                        item.setInt(1, newId);
                        if (menuIndex >= 0) {
                            item.setInt(2, menuIds[menuIndex]);
                        } else {
                            item.setNull(2, Types.INTEGER);
                        }
                        item.setString(3, name);
                        item.setDouble(4, unitPrice);
                        item.setInt(5, quantity);
                        item.setDouble(6, subtotal);
                        item.addBatch();
                    }

                    item.executeBatch();
                }

                con.commit();
                return newId;

            } catch (SQLException e) {
                con.rollback();
                throw e;
            }

        } catch (SQLException e) {
            showDatabaseError("Could not save the transaction. The order was not completed.", e);
            return -1;
        }
    }

    private int findMenuIndex(String name) {
        for (int i = 0; i < menuCount; i++) {
            if (menuNames[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    // ==========================================
    // DATABASE: LOAD THE TRANSACTIONS OF ONE DAY
    // Used by the Transaction History tab so
    // previous days can be viewed without opening
    // the database.
    // ==========================================

    private List<Transaction> loadTransactionsByDate(LocalDate day) {

        List<Transaction> result = new ArrayList<>();

        String sql =
                "SELECT t.transaction_id, t.customer_name, t.transaction_date, "
                + "t.total_amount, t.payment_amount, t.change_amount, "
                + "(SELECT GROUP_CONCAT(CONCAT(ti.item_name, ' x', ti.quantity) SEPARATOR '; ') "
                + "   FROM transaction_items ti "
                + "  WHERE ti.transaction_id = t.transaction_id) AS items "
                + "FROM transactions t "
                + "WHERE t.transaction_date >= ? AND t.transaction_date < ? "
                + "ORDER BY t.transaction_date DESC";

        try (Connection con = getConnection();
             PreparedStatement st = con.prepareStatement(sql)) {

            st.setTimestamp(1, Timestamp.valueOf(day.atStartOfDay()));
            st.setTimestamp(2, Timestamp.valueOf(day.plusDays(1).atStartOfDay()));

            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    String items = rs.getString("items");
                    result.add(new Transaction(
                            String.format("TXN-%04d", rs.getInt("transaction_id")),
                            formatDateTime(rs.getTimestamp("transaction_date")),
                            rs.getString("customer_name"),
                            items == null ? "" : items,
                            rs.getDouble("total_amount"),
                            rs.getDouble("payment_amount"),
                            rs.getDouble("change_amount")));
                }
            }

        } catch (SQLException e) {
            showDatabaseError("Could not load the transaction history.", e);
        }

        return result;
    }

    // ==========================================
    // MAIN WINDOW
    // ==========================================

    private void createGUI() {

        frame = new JFrame("Restaurant Ordering System");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1000, 650);
        frame.setLocationRelativeTo(null);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Customer Order", createOrderPanel());
        tabs.addTab("Manage Menu", createMenuPanel());
        tabs.addTab("Transaction History", createHistoryPanel());

        frame.add(tabs);

        refreshMenuTable();
        refreshMenuCombo();
        refreshHistoryTable();

        frame.setVisible(true);
    }

    // ==========================================
    // CUSTOMER ORDER TAB
    // ==========================================

    private JPanel createOrderPanel() {

        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // Top: customer, menu item, quantity, buttons
        JPanel topPanel = new JPanel(new GridLayout(2, 4, 8, 8));

        topPanel.add(new JLabel("Customer Name:"));
        customerField = new JTextField();
        topPanel.add(customerField);

        topPanel.add(new JLabel("Menu Item:"));
        menuCombo = new JComboBox<>();
        topPanel.add(menuCombo);

        topPanel.add(new JLabel("Quantity:"));
        quantityField = new JTextField("1");
        topPanel.add(quantityField);

        JButton addToCartButton = new JButton("Add to Order");
        addToCartButton.addActionListener(e -> addToCart());
        topPanel.add(addToCartButton);

        JButton clearCartButton = new JButton("Clear Order");
        clearCartButton.addActionListener(e -> clearCart());
        topPanel.add(clearCartButton);

        panel.add(topPanel, BorderLayout.NORTH);

        // Center: order table
        cartModel = new DefaultTableModel(
                new String[]{"Item", "Unit Price (PHP)", "Quantity", "Subtotal (PHP)"}, 0
        ) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        cartTable = new JTable(cartModel);
        panel.add(new JScrollPane(cartTable), BorderLayout.CENTER);

        // Bottom: total, payment, checkout
        JPanel bottomPanel = new JPanel(new GridLayout(2, 3, 8, 8));

        totalLabel = new JLabel("Total: PHP 0.00");
        totalLabel.setFont(new Font("Arial", Font.BOLD, 18));
        bottomPanel.add(totalLabel);

        bottomPanel.add(new JLabel("Payment (PHP):"));
        paymentField = new JTextField();
        bottomPanel.add(paymentField);

        JButton checkoutButton = new JButton("Pay / Complete Order");
        checkoutButton.addActionListener(e -> checkout());
        bottomPanel.add(checkoutButton);

        bottomPanel.add(new JLabel("An order is saved only after successful payment."));

        panel.add(bottomPanel, BorderLayout.SOUTH);
        return panel;
    }

    private void addToCart() {

        if (menuCombo.getSelectedIndex() < 0) {
            showMessage("There are no menu items available.");
            return;
        }

        int quantity;
        try {
            quantity = Integer.parseInt(quantityField.getText().trim());
            if (quantity <= 0) {
                showMessage("Quantity must be greater than zero.");
                return;
            }
        } catch (NumberFormatException ex) {
            showMessage("Please enter a valid whole-number quantity.");
            return;
        }

        int index = menuCombo.getSelectedIndex();
        String name = menuNames[index];
        double price = menuPrices[index];

        // If the item is already in the order, increase its quantity
        for (int row = 0; row < cartModel.getRowCount(); row++) {
            if (cartModel.getValueAt(row, 0).toString().equals(name)) {
                int oldQuantity = Integer.parseInt(cartModel.getValueAt(row, 2).toString());
                int newQuantity = oldQuantity + quantity;
                cartModel.setValueAt(newQuantity, row, 2);
                cartModel.setValueAt(String.format("%.2f", price * newQuantity), row, 3);
                updateCartTotal();
                quantityField.setText("1");
                return;
            }
        }

        cartModel.addRow(new Object[]{
                name, String.format("%.2f", price), quantity,
                String.format("%.2f", price * quantity)
        });
        updateCartTotal();
        quantityField.setText("1");
    }

    private void updateCartTotal() {
        cartTotal = 0.0;
        for (int row = 0; row < cartModel.getRowCount(); row++) {
            cartTotal += Double.parseDouble(cartModel.getValueAt(row, 3).toString());
        }
        totalLabel.setText(String.format("Total: PHP %.2f", cartTotal));
    }

    private void clearCart() {
        cartModel.setRowCount(0);
        cartTotal = 0.0;
        totalLabel.setText("Total: PHP 0.00");
        paymentField.setText("");
    }

    private void checkout() {

        String customerName = customerField.getText().trim();

        if (customerName.isEmpty()) {
            showMessage("Please enter the customer name.");
            return;
        }
        if (cartModel.getRowCount() == 0 || cartTotal <= 0) {
            showMessage("Please add at least one item to the order.");
            return;
        }

        double payment;
        try {
            payment = Double.parseDouble(paymentField.getText().trim());
            if (payment < cartTotal) {
                showMessage("Insufficient payment. Please enter at least PHP "
                        + String.format("%.2f", cartTotal) + ".");
                return;
            }
        } catch (NumberFormatException ex) {
            showMessage("Please enter a valid payment amount.");
            return;
        }

        double total = cartTotal;
        double change = payment - total;

        // 1. Save to the database (permanent record)
        int savedId = saveTransactionToDatabase(customerName, total, payment, change);
        if (savedId < 0) {
            return; // saving failed, keep the order so the cashier can try again
        }

        String transactionId = String.format("TXN-%04d", savedId);
        String dateTime = getPhilippineDateTime();

        String receipt = createReceipt(transactionId, dateTime, customerName,
                total, payment, change);

        // 2. Reload the History tab from the database
        refreshHistoryTable();

        // 3. Show the receipt in a monospaced text area so the columns line up
        JTextArea receiptArea = new JTextArea(receipt);
        receiptArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        receiptArea.setEditable(false);
        JOptionPane.showMessageDialog(
                frame, receiptArea, "Restaurant Receipt", JOptionPane.INFORMATION_MESSAGE);

        customerField.setText("");
        clearCart();
    }

    private String createReceipt(String id, String dateTime, String customer,
                                 double total, double payment, double change) {

        StringBuilder receipt = new StringBuilder();

        receipt.append("====================================\n")
               .append("        RESTAURANT RECEIPT\n")
               .append("====================================\n")
               .append("Transaction ID: ").append(id).append("\n")
               .append("Order Time: ").append(dateTime).append("\n")
               .append("Customer: ").append(customer).append("\n")
               .append("------------------------------------\n");

        for (int row = 0; row < cartModel.getRowCount(); row++) {
            receipt.append(cartModel.getValueAt(row, 0))
                   .append(" x").append(cartModel.getValueAt(row, 2))
                   .append(" = PHP ").append(cartModel.getValueAt(row, 3))
                   .append("\n");
        }

        receipt.append("------------------------------------\n")
               .append(String.format("TOTAL:   PHP %.2f%n", total))
               .append(String.format("PAYMENT: PHP %.2f%n", payment))
               .append(String.format("CHANGE:  PHP %.2f%n", change))
               .append("====================================\n")
               .append("       THANK YOU FOR ORDERING!\n")
               .append("====================================");

        return receipt.toString();
    }

    // ==========================================
    // MANAGE MENU TAB (admin)
    // ==========================================

    private JPanel createMenuPanel() {

        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        menuModel = new DefaultTableModel(
                new String[]{"No.", "Menu Item", "Price (PHP)"}, 0
        ) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        menuTable = new JTable(menuModel);
        panel.add(new JScrollPane(menuTable), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 8));
        JButton addButton = new JButton("Add Menu Item");
        JButton updateButton = new JButton("Update Price");
        JButton removeButton = new JButton("Remove Menu Item");

        addButton.addActionListener(e -> addMenuItem());
        updateButton.addActionListener(e -> updateMenuPrice());
        removeButton.addActionListener(e -> removeMenuItem());

        buttons.add(addButton);
        buttons.add(updateButton);
        buttons.add(removeButton);
        panel.add(buttons, BorderLayout.SOUTH);

        return panel;
    }

    private void refreshMenuTable() {
        if (menuModel == null) {
            return;
        }
        menuModel.setRowCount(0);
        for (int i = 0; i < menuCount; i++) {
            menuModel.addRow(new Object[]{
                    i + 1, menuNames[i], String.format("%.2f", menuPrices[i])
            });
        }
    }

    private void refreshMenuCombo() {
        if (menuCombo == null) {
            return;
        }
        menuCombo.removeAllItems();
        for (int i = 0; i < menuCount; i++) {
            menuCombo.addItem(menuNames[i]);
        }
    }

    private void addMenuItem() {

        if (menuCount >= menuNames.length) {
            showMessage("The menu is full. The current limit is 20 items.");
            return;
        }

        String name = JOptionPane.showInputDialog(frame, "Enter menu item name:");
        if (name == null) {
            return;
        }
        name = name.trim();
        if (name.isEmpty()) {
            showMessage("Menu item name cannot be empty.");
            return;
        }

        for (int i = 0; i < menuCount; i++) {
            if (menuNames[i].equalsIgnoreCase(name)) {
                showMessage("That item is already on the menu.");
                return;
            }
        }

        String priceInput = JOptionPane.showInputDialog(frame, "Enter price (PHP):");
        if (priceInput == null) {
            return;
        }

        double price;
        try {
            price = Double.parseDouble(priceInput.trim());
        } catch (NumberFormatException ex) {
            showMessage("Please enter a valid price.");
            return;
        }

        if (price <= 0) {
            showMessage("Price must be greater than zero.");
            return;
        }

        insertMenuItem(name, price);
    }

    private void updateMenuPrice() {

        int row = menuTable.getSelectedRow();
        if (row < 0) {
            showMessage("Select a menu item from the table first.");
            return;
        }

        String input = JOptionPane.showInputDialog(
                frame, "Enter the new price for " + menuNames[row] + ":");
        if (input == null) {
            return;
        }

        double price;
        try {
            price = Double.parseDouble(input.trim());
        } catch (NumberFormatException ex) {
            showMessage("Please enter a valid price.");
            return;
        }

        if (price <= 0) {
            showMessage("Price must be greater than zero.");
            return;
        }

        updateMenuItemPrice(menuIds[row], price);
    }

    private void removeMenuItem() {

        int row = menuTable.getSelectedRow();
        if (row < 0) {
            showMessage("Select a menu item from the table first.");
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(
                frame, "Remove " + menuNames[row] + "?", "Confirm Removal",
                JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        deleteMenuItem(menuIds[row]);
    }

    // ==========================================
    // TRANSACTION HISTORY TAB
    // Pick a date to view that day's transactions.
    // The data is loaded from the database.
    // ==========================================

    private JPanel createHistoryPanel() {

        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // Top: date picker
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        top.add(new JLabel("Date:"));

        dateSpinner = new JSpinner(
                new SpinnerDateModel(new Date(), null, null, Calendar.DAY_OF_MONTH));
        dateSpinner.setEditor(new JSpinner.DateEditor(dateSpinner, "yyyy-MM-dd"));
        dateSpinner.addChangeListener(e -> refreshHistoryTable());
        top.add(dateSpinner);

        JButton loadButton = new JButton("Load");
        loadButton.addActionListener(e -> refreshHistoryTable());
        top.add(loadButton);

        JButton todayButton = new JButton("Today");
        todayButton.addActionListener(e -> {
            dateSpinner.setValue(new Date());
            refreshHistoryTable();
        });
        top.add(todayButton);

        historySummaryLabel = new JLabel("");
        historySummaryLabel.setFont(new Font("Arial", Font.BOLD, 13));
        top.add(Box.createHorizontalStrut(20));
        top.add(historySummaryLabel);

        panel.add(top, BorderLayout.NORTH);

        historyModel = new DefaultTableModel(
                new String[]{
                        "Transaction ID", "Date / Time", "Customer", "Items",
                        "Total (PHP)", "Payment (PHP)", "Change (PHP)"
                }, 0
        ) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        historyTable = new JTable(historyModel);
        historyTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        historyTable.getColumnModel().getColumn(0).setPreferredWidth(100);
        historyTable.getColumnModel().getColumn(1).setPreferredWidth(190);
        historyTable.getColumnModel().getColumn(2).setPreferredWidth(130);
        historyTable.getColumnModel().getColumn(3).setPreferredWidth(240);
        historyTable.getColumnModel().getColumn(4).setPreferredWidth(100);
        historyTable.getColumnModel().getColumn(5).setPreferredWidth(110);
        historyTable.getColumnModel().getColumn(6).setPreferredWidth(100);

        panel.add(new JScrollPane(historyTable), BorderLayout.CENTER);

        JButton viewButton = new JButton("View Selected Transaction");
        viewButton.addActionListener(e -> viewSelectedTransaction());
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.add(viewButton);
        panel.add(bottom, BorderLayout.SOUTH);

        return panel;
    }

    private void refreshHistoryTable() {
        if (historyModel == null || dateSpinner == null) {
            return;
        }

        LocalDate day = ((Date) dateSpinner.getValue())
                .toInstant().atZone(ZoneId.systemDefault()).toLocalDate();

        shownTransactions = loadTransactionsByDate(day);

        historyModel.setRowCount(0);
        double daySales = 0.0;

        for (Transaction t : shownTransactions) {
            historyModel.addRow(new Object[]{
                    t.getId(), t.getDateTime(), t.getCustomerName(), t.getItems(),
                    String.format("%.2f", t.getTotal()),
                    String.format("%.2f", t.getPayment()),
                    String.format("%.2f", t.getChange())
            });
            daySales += t.getTotal();
        }

        historySummaryLabel.setText(String.format(
                "%d transaction(s)  |  Total sales: PHP %.2f",
                shownTransactions.size(), daySales));
    }

    private void viewSelectedTransaction() {

        int row = historyTable.getSelectedRow();
        if (row < 0) {
            showMessage("Select a transaction from the history table first.");
            return;
        }

        Transaction t = shownTransactions.get(row);

        JOptionPane.showMessageDialog(
                frame, t.getDetails(), "Transaction Details",
                JOptionPane.INFORMATION_MESSAGE);
    }

    // ==========================================
    // HELPER METHODS
    // ==========================================

    private String formatDateTime(Date date) {
        SimpleDateFormat formatter = new SimpleDateFormat("MMMM dd, yyyy - hh:mm:ss a");
        formatter.setTimeZone(TimeZone.getTimeZone("Asia/Manila"));
        return formatter.format(date);
    }

    private String getPhilippineDateTime() {
        return formatDateTime(new Date());
    }

    private void showMessage(String message) {
        JOptionPane.showMessageDialog(frame, message);
    }

    private void showDatabaseError(String message, SQLException e) {
        JOptionPane.showMessageDialog(
                frame,
                message + "\n" + e.getMessage(),
                "Database Error",
                JOptionPane.ERROR_MESSAGE);
    }
}

// ==========================================
// TRANSACTION CLASS
// One completed order. Fields are private and
// final, so they cannot be changed after the
// transaction is created.
// ==========================================

class Transaction {

    private final String id;
    private final String dateTime;
    private final String customerName;
    private final String items;
    private final double total;
    private final double payment;
    private final double change;

    Transaction(String id, String dateTime, String customerName,
                String items, double total, double payment, double change) {

        this.id = id;
        this.dateTime = dateTime;
        this.customerName = customerName;
        this.items = items;
        this.total = total;
        this.payment = payment;
        this.change = change;
    }

    public String getId() { return id; }
    public String getDateTime() { return dateTime; }
    public String getCustomerName() { return customerName; }
    public String getItems() { return items; }
    public double getTotal() { return total; }
    public double getPayment() { return payment; }
    public double getChange() { return change; }

    public String getDetails() {
        return "Transaction ID: " + id + "\n"
                + "Date / Time: " + dateTime + "\n"
                + "Customer: " + customerName + "\n"
                + "Items: " + items + "\n"
                + String.format("Total: PHP %.2f%n", total)
                + String.format("Payment: PHP %.2f%n", payment)
                + String.format("Change: PHP %.2f", change);
    }
}