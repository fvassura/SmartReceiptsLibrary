package co.smartreceipts.android.persistence;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.TimeZone;

import co.smartreceipts.android.BuildConfig;
import co.smartreceipts.android.SmartReceiptsApplication;
import co.smartreceipts.android.date.DateUtils;
import co.smartreceipts.android.model.Distance;
import co.smartreceipts.android.model.Priceable;
import co.smartreceipts.android.model.Receipt;
import co.smartreceipts.android.model.Trip;
import co.smartreceipts.android.model.WBCurrency;
import co.smartreceipts.android.model.factory.DistanceBuilderFactory;
import co.smartreceipts.android.model.factory.ExchangeRateBuilderFactory;
import co.smartreceipts.android.model.factory.PriceBuilderFactory;
import co.smartreceipts.android.model.factory.ReceiptBuilderFactory;
import co.smartreceipts.android.model.factory.TripBuilderFactory;
import co.smartreceipts.android.model.impl.columns.receipts.ReceiptColumnDefinitions;
import co.smartreceipts.android.persistence.database.defaults.TableDefaultCustomizerImpl;
import co.smartreceipts.android.persistence.database.defaults.TableDefaultsCustomizer;
import co.smartreceipts.android.persistence.database.tables.CSVTable;
import co.smartreceipts.android.persistence.database.tables.CategoriesTable;
import co.smartreceipts.android.persistence.database.tables.DistanceTable;
import co.smartreceipts.android.persistence.database.tables.PDFTable;
import co.smartreceipts.android.persistence.database.tables.PaymentMethodsTable;
import co.smartreceipts.android.persistence.database.tables.ReceiptsTable;
import co.smartreceipts.android.persistence.database.tables.Table;
import co.smartreceipts.android.persistence.database.tables.TripsTable;
import co.smartreceipts.android.utils.Utils;
import co.smartreceipts.android.utils.sorting.AlphabeticalCaseInsensitiveCharSequenceComparator;
import co.smartreceipts.android.workers.ImportTask;
import wb.android.autocomplete.AutoCompleteAdapter;
import wb.android.flex.Flex;
import wb.android.storage.StorageManager;

public class DatabaseHelper extends SQLiteOpenHelper implements AutoCompleteAdapter.QueryListener, AutoCompleteAdapter.ItemSelectedListener {

    // Logging Vars
    private static final boolean D = true;
    private static final String TAG = "DatabaseHelper";

    // Database Info
    public static final String DATABASE_NAME = "receipts.db";
    private static final int DATABASE_VERSION = 14;

    @Deprecated
    public static final String NO_DATA = "null"; // TODO: Just set to null

    // Tags
    public static final String TAG_TRIPS_NAME = "Trips";
    public static final String TAG_TRIPS_COST_CENTER = "Trips_CostCenter";
    public static final String TAG_RECEIPTS_NAME = "Receipts";
    public static final String TAG_RECEIPTS_COMMENT = "Receipts_Comment";
    public static final String TAG_DISTANCE_LOCATION = "Distance_Location";

    // InstanceVar
    private static DatabaseHelper INSTANCE = null;

    // Caching Vars
    private Trip[] mTripsCache;
    private boolean mAreTripsValid;
    private final HashMap<Trip, List<Receipt>> mReceiptCache;
    private int mNextReceiptAutoIncrementId = -1;
    private ArrayList<CharSequence> mFullCurrencyList;
    private ArrayList<CharSequence> mMostRecentlyUsedCurrencyList;
    private final ReceiptColumnDefinitions mReceiptColumnDefinitions;

    // Other vars
    private final Context mContext;
    private final Flex mFlex;
    private final PersistenceManager mPersistenceManager;
    private final TableDefaultsCustomizer mCustomizations;

    // Listeners
    private ReceiptAutoCompleteListener mReceiptAutoCompleteListener;

    // Locks
    private final Object mDatabaseLock = new Object();
    private final Object mReceiptCacheLock = new Object();
    private final Object mTripCacheLock = new Object();

    // Tables
    private final List<Table> mTables;
    private final TripsTable mTripsTable;
    private final ReceiptsTable mReceiptsTable;
    private final DistanceTable mDistanceTable;
    private final CategoriesTable mCategoriesTable;
    private final CSVTable mCSVTable;
    private final PDFTable mPDFTable;
    private final PaymentMethodsTable mPaymentMethodsTable;

    // Misc Vars
    private boolean mIsDBOpen = false;

    public interface ReceiptAutoCompleteListener {

        void onReceiptRowAutoCompleteQueryResult(@Nullable String name, @Nullable String price, @Nullable String category);
    }

    private DatabaseHelper(SmartReceiptsApplication application, PersistenceManager persistenceManager, String databasePath) {
        super(application.getApplicationContext(), databasePath, null, DATABASE_VERSION); // Requests the default cursor
        // factory
        mAreTripsValid = false;
        mReceiptCache = new HashMap<>();
        mContext = application.getApplicationContext();
        mFlex = application.getFlex();
        mPersistenceManager = persistenceManager;
        mReceiptColumnDefinitions = new ReceiptColumnDefinitions(mContext, this, mPersistenceManager.getPreferences(), mFlex);
        mCustomizations = new TableDefaultCustomizerImpl(mContext, mReceiptColumnDefinitions);

        // Tables:
        mTables = new ArrayList<>();
        mTripsTable = new TripsTable(this, mPersistenceManager);
        mDistanceTable = new DistanceTable(this, mTripsTable, mPersistenceManager.getPreferences().getDefaultCurreny());
        mCategoriesTable = new CategoriesTable(this);
        mCSVTable = new CSVTable(this, mReceiptColumnDefinitions);
        mPDFTable = new PDFTable(this, mReceiptColumnDefinitions);
        mPaymentMethodsTable = new PaymentMethodsTable(this);
        mReceiptsTable = new ReceiptsTable(this, mTripsTable, mPaymentMethodsTable, mCategoriesTable, mPersistenceManager);
        mTables.add(mTripsTable);
        mTables.add(mDistanceTable);
        mTables.add(mCategoriesTable);
        mTables.add(mCSVTable);
        mTables.add(mPDFTable);
        mTables.add(mPaymentMethodsTable);
        mTables.add(mReceiptsTable);

        this.getReadableDatabase(); // Called here, so onCreate gets called on the UI thread
    }

    public static final DatabaseHelper getInstance(SmartReceiptsApplication application, PersistenceManager persistenceManager) {
        if (INSTANCE == null || !INSTANCE.isOpen()) { // If we don't have an instance or it's closed
            String databasePath = StorageManager.GetRootPath();
            if (BuildConfig.DEBUG) {
                if (databasePath.equals("")) {
                    throw new RuntimeException("The SDCard must be created beforoe GetRootPath is called in DBHelper");
                }
            }
            if (!databasePath.endsWith(File.separator)) {
                databasePath = databasePath + File.separator;
            }
            databasePath = databasePath + DATABASE_NAME;
            INSTANCE = new DatabaseHelper(application, persistenceManager, databasePath);
        }
        return INSTANCE;
    }


    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Begin Abstract Method Overrides
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    @Override
    public void onCreate(final SQLiteDatabase db) {
        for (final Table table : mTables) {
            table.onCreate(db, mCustomizations);
        }

        for (final Table table : mTables) {
            table.onPostCreateUpgrade();
        }

    }

    @Override
    public final void onUpgrade(final SQLiteDatabase db, int oldVersion, final int newVersion) {

        for (final Table table : mTables) {
            table.onUpgrade(db, oldVersion, newVersion, mCustomizations);
        }

        for (final Table table : mTables) {
            table.onPostCreateUpgrade();
        }
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        mIsDBOpen = true;
    }

    @Override
    public synchronized void close() {
        super.close();
        mIsDBOpen = false;
    }

    public boolean isOpen() {
        return mIsDBOpen;
    }

    public void onDestroy() {
        try {
            this.close();
        } catch (Exception e) {
            // This can be called from finalize, so operate cautiously
            Log.e(TAG, e.toString());
        }
    }

    @Override
    protected void finalize() throws Throwable {
        onDestroy(); // Close our resources if we still need
        super.finalize();
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Trip Methods
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    @NonNull
    public final TripsTable getTripsTable() {
        return mTripsTable;
    }

    /**
     * This class is not synchronized! Sync outside of it
     *
     * @param trip
     * @return
     */
    public void getTripPriceAndDailyPrice(final Trip trip) {
        queryTripPrice(trip);
        queryTripDailyPrice(trip);
    }

    /**
     * Queries the trips price and updates this object. This class is not synchronized! Sync outside of it
     *
     * @param trip the trip, which will be updated
     */
    private void queryTripPrice(final Trip trip) {
        final boolean onlyUseExpensable = mPersistenceManager.getPreferences().onlyIncludeExpensableReceiptsInReports();
        final List<Receipt> receipts = mReceiptsTable.getBlocking(trip, true);
        final List<Priceable> prices = new ArrayList<>(receipts.size());
        for (final Receipt receipt : receipts) {
            if (!onlyUseExpensable || receipt.isExpensable()) {
                prices.add(receipt);
            }
        }

        if (mPersistenceManager.getPreferences().getShouldTheDistancePriceBeIncludedInReports()) {
            final List<Distance> distances = mDistanceTable.getBlocking(trip, true);
            for (final Distance distance : distances) {
                prices.add(distance);
            }
        }

        trip.setPrice(new PriceBuilderFactory().setPriceables(prices, trip.getTripCurrency()).build());
    }

    /**
     * Queries the trips daily total price and updates this object. This class is not synchronized! Sync outside of it
     *
     * @param trip the trip, which will be updated
     */
    private void queryTripDailyPrice(final Trip trip) {
        final boolean onlyUseExpensable = mPersistenceManager.getPreferences().onlyIncludeExpensableReceiptsInReports();
        final List<Receipt> receipts = mReceiptsTable.getBlocking(trip, true);
        final List<Priceable> prices = new ArrayList<>(receipts.size());
        for (final Receipt receipt : receipts) {
            if (!onlyUseExpensable || receipt.isExpensable()) {
                if(DateUtils.isToday(receipt.getDate())) {
                    prices.add(receipt);
                }
            }
        }

        if (mPersistenceManager.getPreferences().getShouldTheDistancePriceBeIncludedInReports()) {
            final List<Distance> distances = mDistanceTable.getBlocking(trip, true);
            for (final Distance distance : distances) {
                if(DateUtils.isToday(distance.getDate())) {
                    prices.add(distance);
                }
            }
        }

        trip.setDailySubTotal(new PriceBuilderFactory().setPriceables(prices, trip.getTripCurrency()).build());
    }

    public List<Receipt> getReceiptsSerial(final Trip trip, final boolean desc) { // Only the email writer should
        return getReceiptsHelper(trip, desc);
    }

    private final List<Receipt> getReceiptsHelper(final Trip trip, final boolean desc) {
        List<Receipt> receipts;
        if (trip == null) {
            return new ArrayList<Receipt>();
        }
        synchronized (mDatabaseLock) {
            SQLiteDatabase db = null;
            Cursor c = null;
            try {
                db = this.getReadableDatabase();
                c = db.query(ReceiptsTable.TABLE_NAME, null, ReceiptsTable.COLUMN_PARENT + "= ?", new String[]{trip.getName()}, null, null, ReceiptsTable.COLUMN_DATE + ((desc) ? " DESC" : " ASC"));
                if (c != null && c.moveToFirst()) {
                    receipts = new ArrayList<Receipt>(c.getCount());
                    final int idIndex = c.getColumnIndex(ReceiptsTable.COLUMN_ID);
                    final int pathIndex = c.getColumnIndex(ReceiptsTable.COLUMN_PATH);
                    final int nameIndex = c.getColumnIndex(ReceiptsTable.COLUMN_NAME);
                    final int categoryIndex = c.getColumnIndex(ReceiptsTable.COLUMN_CATEGORY);
                    final int priceIndex = c.getColumnIndex(ReceiptsTable.COLUMN_PRICE);
                    final int taxIndex = c.getColumnIndex(ReceiptsTable.COLUMN_TAX);
                    final int exchangeRateIndex = c.getColumnIndex(ReceiptsTable.COLUMN_EXCHANGE_RATE);
                    final int dateIndex = c.getColumnIndex(ReceiptsTable.COLUMN_DATE);
                    final int timeZoneIndex = c.getColumnIndex(ReceiptsTable.COLUMN_TIMEZONE);
                    final int commentIndex = c.getColumnIndex(ReceiptsTable.COLUMN_COMMENT);
                    final int expenseableIndex = c.getColumnIndex(ReceiptsTable.COLUMN_EXPENSEABLE);
                    final int currencyIndex = c.getColumnIndex(ReceiptsTable.COLUMN_ISO4217);
                    final int fullpageIndex = c.getColumnIndex(ReceiptsTable.COLUMN_NOTFULLPAGEIMAGE);
                    final int paymentMethodIdIndex = c.getColumnIndex(ReceiptsTable.COLUMN_PAYMENT_METHOD_ID);
                    final int extra_edittext_1_Index = c.getColumnIndex(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_1);
                    final int extra_edittext_2_Index = c.getColumnIndex(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_2);
                    final int extra_edittext_3_Index = c.getColumnIndex(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_3);
                    do {
                        final int id = c.getInt(idIndex);
                        final String path = c.getString(pathIndex);
                        final String name = c.getString(nameIndex);
                        final String category = c.getString(categoryIndex);
                        final double priceDouble = c.getDouble(priceIndex);
                        final double taxDouble = c.getDouble(taxIndex);
                        final double exchangeRateDouble = c.getDouble(exchangeRateIndex);
                        final String priceString = c.getString(priceIndex);
                        final String taxString = c.getString(taxIndex);
                        final String exchangeRateString = c.getString(exchangeRateIndex);
                        final long date = c.getLong(dateIndex);
                        final String timezone = (timeZoneIndex > 0) ? c.getString(timeZoneIndex) : null;
                        final String comment = c.getString(commentIndex);
                        final boolean expensable = c.getInt(expenseableIndex) > 0;
                        final String currency = c.getString(currencyIndex);
                        final boolean fullpage = !(c.getInt(fullpageIndex) > 0);
                        final int paymentMethodId = c.getInt(paymentMethodIdIndex); // Not using a join, since we need
                        final String extra_edittext_1 = c.getString(extra_edittext_1_Index);
                        final String extra_edittext_2 = c.getString(extra_edittext_2_Index);
                        final String extra_edittext_3 = c.getString(extra_edittext_3_Index);
                        File img = null;
                        if (!TextUtils.isEmpty(path) && !DatabaseHelper.NO_DATA.equals(path)) {
                            img = mPersistenceManager.getStorageManager().getFile(trip.getDirectory(), path);
                        }
                        final ReceiptBuilderFactory builder = new ReceiptBuilderFactory(id);
                        builder.setTrip(trip).setName(name).setCategory(category).setImage(img).setDate(date).setTimeZone(timezone).setComment(comment).setIsExpenseable(expensable).setCurrency(currency).setIsFullPage(fullpage).setIndex(c.getPosition() + 1).setPaymentMethod(getPaymentMethodsTable().findByPrimaryKey(paymentMethodId).toBlocking().first()).setExtraEditText1(extra_edittext_1).setExtraEditText2(extra_edittext_2).setExtraEditText3(extra_edittext_3);
                        /**
                         * Please note that a very frustrating bug exists here. Android cursors only return the first 6
                         * characters of a price string if that string contains a '.' character. It returns all of them
                         * if not. This means we'll break for prices over 5 digits unless we are using a comma separator, 
                         * which we'd do in the EU. Stupid check below to un-break this. Stupid Android.
                         *
                         * TODO: Longer term, everything should be saved with a decimal point
                         * https://code.google.com/p/android/issues/detail?id=22219
                         */
                        if (!TextUtils.isEmpty(priceString) && priceString.contains(",")) {
                            builder.setPrice(priceString);
                        } else {
                            builder.setPrice(priceDouble);
                        }
                        if (!TextUtils.isEmpty(taxString) && taxString.contains(",")) {
                            builder.setTax(taxString);
                        } else {
                            builder.setTax(taxDouble);
                        }
                        final ExchangeRateBuilderFactory exchangeRateBuilder = new ExchangeRateBuilderFactory().setBaseCurrency(currency);
                        if (!TextUtils.isEmpty(exchangeRateString) && exchangeRateString.contains(",")) {
                            exchangeRateBuilder.setRate(trip.getTripCurrency(), exchangeRateString);
                        } else {
                            exchangeRateBuilder.setRate(trip.getTripCurrency(), exchangeRateDouble);
                        }
                        builder.setExchangeRate(exchangeRateBuilder.build());
                        receipts.add(builder.build());
                    }
                    while (c.moveToNext());
                } else {
                    receipts = new ArrayList<Receipt>();
                }
            } finally { // Close the cursor and db to avoid memory leaks
                if (c != null) {
                    c.close();
                }
            }
        }
        synchronized (mReceiptCacheLock) {
            if (desc) {
                mReceiptCache.put(trip, receipts);
            }
        }
        return receipts;
    }

    public final Receipt updateReceiptFile(final Receipt oldReceipt, final File file) {
        synchronized (mDatabaseLock) {
            SQLiteDatabase db = null;
            try {
                db = this.getReadableDatabase();
                ContentValues values = new ContentValues(1);
                if (file == null) {
                    values.put(ReceiptsTable.COLUMN_PATH, NO_DATA);
                } else {
                    values.put(ReceiptsTable.COLUMN_PATH, file.getName());
                }
                if (values == null || (db.update(ReceiptsTable.TABLE_NAME, values, ReceiptsTable.COLUMN_ID + " = ?", new String[]{Integer.toString(oldReceipt.getId())}) == 0)) {
                    return null;
                } else {
                    synchronized (mReceiptCacheLock) {
                        mNextReceiptAutoIncrementId = -1;
                        mReceiptCache.remove(oldReceipt.getTrip());
                    }
                    oldReceipt.setFile(file);
                    return oldReceipt;
                }
            } catch (SQLException e) {
                return null;
            }
        }
    }

    public int getNextReceiptAutoIncremenetIdSerial() {
        return getNextReceiptAutoIncremenetIdHelper();
    }

    private int getNextReceiptAutoIncremenetIdHelper() {
        if (mNextReceiptAutoIncrementId > 0) {
            return mNextReceiptAutoIncrementId;
        }
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            synchronized (mDatabaseLock) {

                c = db.rawQuery("SELECT seq FROM SQLITE_SEQUENCE WHERE name=?", new String[]{ReceiptsTable.TABLE_NAME});
                if (c != null && c.moveToFirst() && c.getColumnCount() > 0) {
                    mNextReceiptAutoIncrementId = c.getInt(0) + 1;
                } else {
                    mNextReceiptAutoIncrementId = 1;
                }
                return mNextReceiptAutoIncrementId;
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Categories Methods
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    public final ArrayList<CharSequence> getCurrenciesList() {
        if (mFullCurrencyList != null) {
            return mFullCurrencyList;
        }
        mFullCurrencyList = new ArrayList<>();
        mFullCurrencyList.addAll(WBCurrency.getIso4217CurrencyCodes());
        mFullCurrencyList.addAll(WBCurrency.getNonIso4217CurrencyCodes());
        Collections.sort(mFullCurrencyList, new AlphabeticalCaseInsensitiveCharSequenceComparator());
        mFullCurrencyList.addAll(0, getMostRecentlyUsedCurrencies());
        return mFullCurrencyList;
    }

    private List<CharSequence> getMostRecentlyUsedCurrencies() {
        if (mMostRecentlyUsedCurrencyList != null) {
            return mMostRecentlyUsedCurrencyList;
        }
        mMostRecentlyUsedCurrencyList = new ArrayList<>();
        final String query = "SELECT " + ReceiptsTable.COLUMN_ISO4217 + ", COUNT(*) FROM " + ReceiptsTable.TABLE_NAME + " GROUP BY " + ReceiptsTable.COLUMN_ISO4217;
        synchronized (mDatabaseLock) {
            Cursor cursor = null;
            try {
                final SQLiteDatabase db = this.getReadableDatabase();
                cursor = db.rawQuery(query, new String[0]);
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        mMostRecentlyUsedCurrencyList.add(cursor.getString(0));
                    }
                    while (cursor.moveToNext());
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        Collections.sort(mMostRecentlyUsedCurrencyList, new AlphabeticalCaseInsensitiveCharSequenceComparator());
        return mMostRecentlyUsedCurrencyList;
    }

    public final ArrayList<CharSequence> getCategoriesList() {
        return new ArrayList<>();
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Columns Methods
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    @NonNull
    public final ReceiptsTable getReceiptsTable() {
        return mReceiptsTable;
    }

    @NonNull
    public final DistanceTable getDistanceTable() {
        return mDistanceTable;
    }

    @NonNull
    public final CategoriesTable getCategoriesTable() {
        return mCategoriesTable;
    }

    @NonNull
    public final CSVTable getCSVTable() {
        return mCSVTable;
    }

    @NonNull
    public final PDFTable getPDFTable() {
        return mPDFTable;
    }

    @NonNull
    public final PaymentMethodsTable getPaymentMethodsTable() { return mPaymentMethodsTable; }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Utilities
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    public final synchronized boolean merge(String dbPath, String packageName, boolean overwrite) {
        mAreTripsValid = false;
        mReceiptCache.clear();
        synchronized (mDatabaseLock) {
            SQLiteDatabase importDB = null, currDB = null;
            Cursor c = null, countCursor = null;
            try {
                if (dbPath == null) {
                    mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Null database file");
                    return false;
                }
                currDB = this.getWritableDatabase();
                importDB = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READWRITE);
                // Merge Trips
                try {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Merging Trips");
                    }
                    mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Merging Trips");
                    c = importDB.query(TripsTable.TABLE_NAME, null, null, null, null, null, TripsTable.COLUMN_TO + " DESC");
                    if (c != null && c.moveToFirst()) {
                        final int nameIndex = c.getColumnIndex(TripsTable.COLUMN_NAME);
                        final int fromIndex = c.getColumnIndex(TripsTable.COLUMN_FROM);
                        final int fromTimeZoneIndex = c.getColumnIndex(TripsTable.COLUMN_FROM_TIMEZONE);
                        final int toIndex = c.getColumnIndex(TripsTable.COLUMN_TO);
                        final int toTimeZoneIndex = c.getColumnIndex(TripsTable.COLUMN_TO_TIMEZONE);
                        final int commentIndex = c.getColumnIndex(TripsTable.COLUMN_COMMENT);
                        final int filtersIndex = c.getColumnIndex(TripsTable.COLUMN_FILTERS);
                        final int costCenterIndex = c.getColumnIndex(TripsTable.COLUMN_COST_CENTER);
                        final int processingStatusIndex = c.getColumnIndex(TripsTable.COLUMN_PROCESSING_STATUS);
                        final int defaultCurrencyIndex = c.getColumnIndex(TripsTable.COLUMN_DEFAULT_CURRENCY);
                        do {
                            String name = getString(c, nameIndex, "");
                            if (name.contains("wb.receipts")) { // Backwards compatibility stuff
                                if (packageName.equalsIgnoreCase("wb.receipts")) {
                                    name = name.replace("wb.receiptspro/", "wb.receipts/");
                                } else if (packageName.equalsIgnoreCase("wb.receiptspro")) {
                                    name = name.replace("wb.receipts/", "wb.receiptspro/");
                                }
                                File f = new File(name);
                                name = f.getName();
                            }
                            final long from = getLong(c, fromIndex, 0L);
                            final long to = getLong(c, toIndex, 0L);
                            final String comment = getString(c, commentIndex, "");
                            final String filters = getString(c, filtersIndex, "");
                            final String costCenter = getString(c, costCenterIndex, "");
                            final String processingStatus = getString(c, processingStatusIndex, "");
                            final String defaultCurrency = getString(c, defaultCurrencyIndex, mPersistenceManager.getPreferences().getDefaultCurreny());
                            ContentValues values = new ContentValues(10);
                            values.put(TripsTable.COLUMN_NAME, name);
                            values.put(TripsTable.COLUMN_FROM, from);
                            values.put(TripsTable.COLUMN_TO, to);
                            values.put(TripsTable.COLUMN_COMMENT, comment);
                            values.put(TripsTable.COLUMN_FILTERS, filters);
                            values.put(TripsTable.COLUMN_COST_CENTER, costCenter);
                            values.put(TripsTable.COLUMN_PROCESSING_STATUS, processingStatus);
                            values.put(TripsTable.COLUMN_DEFAULT_CURRENCY, defaultCurrency);
                            if (fromTimeZoneIndex > 0) {
                                final String fromTimeZome = c.getString(fromTimeZoneIndex);
                                values.put(TripsTable.COLUMN_FROM_TIMEZONE, fromTimeZome);
                            }
                            if (toTimeZoneIndex > 0) {
                                final String toTimeZome = c.getString(toTimeZoneIndex);
                                values.put(TripsTable.COLUMN_TO_TIMEZONE, toTimeZome);
                            }
                            if (overwrite) {
                                currDB.insertWithOnConflict(TripsTable.TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE);
                            } else {
                                currDB.insertWithOnConflict(TripsTable.TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_IGNORE);
                            }
                        }
                        while (c.moveToNext());
                    }
                } catch (SQLiteException e) {
                    if (BuildConfig.DEBUG) {
                        Log.e(TAG, e.toString(), e); // Occurs if Table does not exist
                    }
                    mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Caught sql exception during import at [a1]: " + Utils.getStackTrace(e));
                } finally {
                    if (c != null && !c.isClosed()) {
                        c.close();
                        c = null;
                    }
                }

                // Merge Receipts
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Merging Receipts");
                }
                mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Merging Receipts");
                try {
                    final String queryCount = "SELECT COUNT(*), " + ReceiptsTable.COLUMN_ID + " FROM " + ReceiptsTable.TABLE_NAME + " WHERE " + ReceiptsTable.COLUMN_PATH + "=? AND " + ReceiptsTable.COLUMN_NAME + "=? AND " + ReceiptsTable.COLUMN_DATE + "=?";
                    c = importDB.query(ReceiptsTable.TABLE_NAME, null, null, null, null, null, null);
                    if (c != null && c.moveToFirst()) {
                        final int pathIndex = c.getColumnIndex(ReceiptsTable.COLUMN_PATH);
                        final int nameIndex = c.getColumnIndex(ReceiptsTable.COLUMN_NAME);
                        final int parentIndex = c.getColumnIndex(ReceiptsTable.COLUMN_PARENT);
                        final int categoryIndex = c.getColumnIndex(ReceiptsTable.COLUMN_CATEGORY);
                        final int priceIndex = c.getColumnIndex(ReceiptsTable.COLUMN_PRICE);
                        final int dateIndex = c.getColumnIndex(ReceiptsTable.COLUMN_DATE);
                        final int commentIndex = c.getColumnIndex(ReceiptsTable.COLUMN_COMMENT);
                        final int expenseableIndex = c.getColumnIndex(ReceiptsTable.COLUMN_EXPENSEABLE);
                        final int currencyIndex = c.getColumnIndex(ReceiptsTable.COLUMN_ISO4217);
                        final int fullpageIndex = c.getColumnIndex(ReceiptsTable.COLUMN_NOTFULLPAGEIMAGE);
                        final int extra_edittext_1_Index = c.getColumnIndex(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_1);
                        final int extra_edittext_2_Index = c.getColumnIndex(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_2);
                        final int extra_edittext_3_Index = c.getColumnIndex(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_3);
                        final int taxIndex = c.getColumnIndex(ReceiptsTable.COLUMN_TAX);
                        final int timeZoneIndex = c.getColumnIndex(ReceiptsTable.COLUMN_TIMEZONE);
                        final int paymentMethodIndex = c.getColumnIndex(ReceiptsTable.COLUMN_PAYMENT_METHOD_ID);
                        final int processingStatusIndex = c.getColumnIndex(ReceiptsTable.COLUMN_PROCESSING_STATUS);
                        final int exchangeRateIndex = c.getColumnIndex(ReceiptsTable.COLUMN_EXCHANGE_RATE);
                        do {
                            final String oldPath = getString(c, pathIndex, "");
                            String newPath = oldPath != null ? oldPath : "";
                            if (newPath.contains("wb.receipts")) { // Backwards compatibility stuff
                                if (packageName.equalsIgnoreCase("wb.receipts")) {
                                    newPath = oldPath.replace("wb.receiptspro/", "wb.receipts/");
                                } else if (packageName.equalsIgnoreCase("wb.receiptspro")) {
                                    newPath = oldPath.replace("wb.receipts/", "wb.receiptspro/");
                                }
                                File f = new File(newPath);
                                newPath = f.getName();
                            }
                            final String name = getString(c, nameIndex, "");
                            final String oldParent = getString(c, parentIndex, "");
                            String newParent = oldParent != null ? oldParent : "";
                            if (newParent.contains("wb.receipts")) { // Backwards compatibility stuff
                                if (packageName.equalsIgnoreCase("wb.receipts")) {
                                    newParent = oldParent.replace("wb.receiptspro/", "wb.receipts/");
                                } else if (packageName.equalsIgnoreCase("wb.receiptspro")) {
                                    newParent = oldParent.replace("wb.receipts/", "wb.receiptspro/");
                                }
                                File f = new File(newParent);
                                newParent = f.getName();
                            }
                            final String category = getString(c, categoryIndex, "");
                            final BigDecimal price = getDecimal(c, priceIndex);
                            final long date = getLong(c, dateIndex, 0L);
                            final String comment = getString(c, commentIndex, "");
                            final boolean expensable = getBoolean(c, expenseableIndex, true);
                            final String currency = getString(c, currencyIndex, mPersistenceManager.getPreferences().getDefaultCurreny());
                            final boolean fullpage = getBoolean(c, fullpageIndex, false);
                            final String extra_edittext_1 = getString(c, extra_edittext_1_Index, null);
                            final String extra_edittext_2 = getString(c, extra_edittext_2_Index, null);
                            final String extra_edittext_3 = getString(c, extra_edittext_3_Index, null);
                            final BigDecimal tax = getDecimal(c, taxIndex);
                            final int paymentMethod = getInt(c, paymentMethodIndex, 0);
                            final String processingStatus = getString(c, processingStatusIndex, "");
                            final BigDecimal exchangeRate = getDecimal(c, exchangeRateIndex);
                            try {
                                countCursor = currDB.rawQuery(queryCount, new String[]{newPath, name, Long.toString(date)});
                                if (countCursor != null && countCursor.moveToFirst()) {
                                    int count = countCursor.getInt(0);
                                    int updateID = countCursor.getInt(1);
                                    final ContentValues values = new ContentValues(14);
                                    values.put(ReceiptsTable.COLUMN_PATH, newPath);
                                    values.put(ReceiptsTable.COLUMN_NAME, name);
                                    values.put(ReceiptsTable.COLUMN_PARENT, newParent);
                                    values.put(ReceiptsTable.COLUMN_CATEGORY, category);
                                    values.put(ReceiptsTable.COLUMN_PRICE, price.doubleValue());
                                    values.put(ReceiptsTable.COLUMN_DATE, date);
                                    values.put(ReceiptsTable.COLUMN_COMMENT, comment);
                                    values.put(ReceiptsTable.COLUMN_EXPENSEABLE, expensable);
                                    values.put(ReceiptsTable.COLUMN_ISO4217, currency);
                                    values.put(ReceiptsTable.COLUMN_NOTFULLPAGEIMAGE, fullpage);
                                    values.put(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_1, extra_edittext_1);
                                    values.put(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_2, extra_edittext_2);
                                    values.put(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_3, extra_edittext_3);
                                    values.put(ReceiptsTable.COLUMN_TAX, tax.doubleValue());
                                    values.put(ReceiptsTable.COLUMN_PROCESSING_STATUS, processingStatus);
                                    values.put(ReceiptsTable.COLUMN_EXCHANGE_RATE, exchangeRate.doubleValue());
                                    if (timeZoneIndex > 0) {
                                        final String timeZone = c.getString(timeZoneIndex);
                                        values.put(ReceiptsTable.COLUMN_TIMEZONE, timeZone);
                                    }
                                    values.put(ReceiptsTable.COLUMN_PAYMENT_METHOD_ID, paymentMethod);
                                    if (count > 0 && overwrite) { // Update
                                        currDB.update(ReceiptsTable.TABLE_NAME, values, ReceiptsTable.COLUMN_ID + " = ?", new String[]{Integer.toString(updateID)});
                                    } else { // insert
                                        if (overwrite) {
                                            currDB.insertWithOnConflict(ReceiptsTable.TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE);
                                        } else if (count == 0) {
                                            // If we're not overwriting anything, let's check that there are no entries here
                                            currDB.insertWithOnConflict(ReceiptsTable.TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_IGNORE);
                                        }
                                    }
                                }
                            } finally {
                                if (countCursor != null && !countCursor.isClosed()) {
                                    countCursor.close();
                                    countCursor = null;
                                }
                            }
                        }
                        while (c.moveToNext());
                    }
                } catch (SQLiteException e) {
                    if (BuildConfig.DEBUG) {
                        Log.e(TAG, e.toString(), e); // Occurs if Table does not exist
                    }
                    mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Caught sql exception during import at [a2]: " + Utils.getStackTrace(e));
                } finally {
                    if (c != null && !c.isClosed()) {
                        c.close();
                        c = null;
                    }
                }

                // Merge Categories
                // No clean way to merge (since auto-increment is not guaranteed to have any order and there isn't
                // enough outlying data) => Always overwirte
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Merging Categories");
                }
                mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Merging Categories");
                try {
                    c = importDB.query(CategoriesTable.TABLE_NAME, null, null, null, null, null, null);
                    if (c != null && c.moveToFirst()) {
                        currDB.delete(CategoriesTable.TABLE_NAME, null, null); // DELETE FROM Categories
                        final int nameIndex = c.getColumnIndex(CategoriesTable.COLUMN_NAME);
                        final int codeIndex = c.getColumnIndex(CategoriesTable.COLUMN_CODE);
                        final int breakdownIndex = c.getColumnIndex(CategoriesTable.COLUMN_BREAKDOWN);
                        do {
                            final String name = getString(c, nameIndex, "");
                            final String code = getString(c, codeIndex, "");
                            final boolean breakdown = getBoolean(c, breakdownIndex, true);
                            ContentValues values = new ContentValues(3);
                            values.put(CategoriesTable.COLUMN_NAME, name);
                            values.put(CategoriesTable.COLUMN_CODE, code);
                            values.put(CategoriesTable.COLUMN_BREAKDOWN, breakdown);
                            currDB.insert(CategoriesTable.TABLE_NAME, null, values);
                        }
                        while (c.moveToNext());
                    }
                } catch (SQLiteException e) {
                    if (BuildConfig.DEBUG) {
                        Log.e(TAG, e.toString(), e); // Occurs if Table does not exist
                    }
                    mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Caught sql exception during import at [a3]: " + Utils.getStackTrace(e));
                } finally {
                    if (c != null && !c.isClosed()) {
                        c.close();
                        c = null;
                    }
                }

                // Merge CSV
                // No clean way to merge (since auto-increment is not guaranteed to have any order and there isn't
                // enough outlying data) => Always overwirte
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Merging CSV");
                }
                mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Merging CSV");
                try {
                    c = importDB.query(CSVTable.TABLE_NAME, null, null, null, null, null, null);
                    if (c != null && c.moveToFirst()) {
                        currDB.delete(CSVTable.TABLE_NAME, null, null); // DELETE * FROM CSVTable
                        final int idxIndex = c.getColumnIndex(CSVTable.COLUMN_ID);
                        final int typeIndex = c.getColumnIndex(CSVTable.COLUMN_TYPE);
                        do {
                            final int index = getInt(c, idxIndex, 0);
                            final String type = getString(c, typeIndex, "");
                            ContentValues values = new ContentValues(2);
                            values.put(CSVTable.COLUMN_ID, index);
                            values.put(CSVTable.COLUMN_TYPE, type);
                            currDB.insert(CSVTable.TABLE_NAME, null, values);
                        }
                        while (c.moveToNext());
                    }
                } catch (SQLiteException e) {
                    if (BuildConfig.DEBUG) {
                        Log.e(TAG, e.toString(), e); // Occurs if Table does not exist
                    }
                    mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Caught sql exception during import at [a4]: " + Utils.getStackTrace(e));
                } finally {
                    if (c != null && !c.isClosed()) {
                        c.close();
                        c = null;
                    }
                }

                // Merge PDF
                // No clean way to merge (since auto-increment is not guaranteed to have any order and there isn't
                // enough outlying data) => Always overwirte
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Merging PDF");
                }
                mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Merging PDF");
                try {
                    c = importDB.query(PDFTable.TABLE_NAME, null, null, null, null, null, null);
                    if (c != null && c.moveToFirst()) {
                        currDB.delete(PDFTable.TABLE_NAME, null, null); // DELETE * FROM PDFTable
                        final int idxIndex = c.getColumnIndex(PDFTable.COLUMN_ID);
                        final int typeIndex = c.getColumnIndex(PDFTable.COLUMN_TYPE);
                        do {
                            final int index = getInt(c, idxIndex, 0);
                            final String type = getString(c, typeIndex, "");
                            ContentValues values = new ContentValues(2);
                            values.put(PDFTable.COLUMN_ID, index);
                            values.put(PDFTable.COLUMN_TYPE, type);
                            currDB.insert(PDFTable.TABLE_NAME, null, values);
                        }
                        while (c.moveToNext());
                    }
                } catch (SQLiteException e) {
                    if (BuildConfig.DEBUG) {
                        Log.e(TAG, e.toString(), e); // Occurs if Table does not exist
                    }
                    mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Caught sql exception during import at [a5]: " + Utils.getStackTrace(e));
                } finally {
                    if (c != null && !c.isClosed()) {
                        c.close();
                        c = null;
                    }
                }

                // Merge Payment methods
                // No clean way to merge (since auto-increment is not guaranteed to have any order and there isn't
                // enough outlying data) => Always overwirte
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Merging Payment Methods");
                }
                mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Payment Methods");
                try {
                    c = importDB.query(PaymentMethodsTable.TABLE_NAME, null, null, null, null, null, null);
                    if (c != null && c.moveToFirst()) {
                        currDB.delete(PaymentMethodsTable.TABLE_NAME, null, null); // DELETE * FROM PaymentMethodsTable
                        final int idxIndex = c.getColumnIndex(PaymentMethodsTable.COLUMN_ID);
                        final int typeIndex = c.getColumnIndex(PaymentMethodsTable.COLUMN_METHOD);
                        do {
                            final int index = getInt(c, idxIndex, 0);
                            final String type = getString(c, typeIndex, "");
                            ContentValues values = new ContentValues(2);
                            values.put(PaymentMethodsTable.COLUMN_ID, index);
                            values.put(PaymentMethodsTable.COLUMN_METHOD, type);
                            currDB.insert(PaymentMethodsTable.TABLE_NAME, null, values);
                        }
                        while (c.moveToNext());
                    }
                } catch (SQLiteException e) {
                    if (BuildConfig.DEBUG) {
                        Log.e(TAG, e.toString(), e); // Occurs if Table does not exist
                    }
                    mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Caught sql exception during import at [a6]: " + Utils.getStackTrace(e));
                } finally {
                    if (c != null && !c.isClosed()) {
                        c.close();
                        c = null;
                    }
                }

                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Merging Distance");
                }
                mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Distance");
                try {
                    c = importDB.query(DistanceTable.TABLE_NAME, null, null, null, null, null, null);
                    if (c != null && c.moveToFirst()) {
                        final String distanceCountQuery = "SELECT COUNT(*), " + DistanceTable.COLUMN_ID + " FROM " + DistanceTable.TABLE_NAME + " WHERE " + DistanceTable.COLUMN_PARENT + "=? AND " + DistanceTable.COLUMN_LOCATION + "=? AND " + DistanceTable.COLUMN_DATE + "=?";
                        final int parentTripIndex = c.getColumnIndex(DistanceTable.COLUMN_PARENT);
                        final int locationIndex = c.getColumnIndex(DistanceTable.COLUMN_LOCATION);
                        final int distanceIndex = c.getColumnIndex(DistanceTable.COLUMN_DISTANCE);
                        final int rateIndex = c.getColumnIndex(DistanceTable.COLUMN_RATE);
                        final int currencyIndex = c.getColumnIndex(DistanceTable.COLUMN_RATE_CURRENCY);
                        final int dateIndex = c.getColumnIndex(DistanceTable.COLUMN_DATE);
                        final int timezoneIndex = c.getColumnIndex(DistanceTable.COLUMN_TIMEZONE);
                        final int commentIndex = c.getColumnIndex(DistanceTable.COLUMN_COMMENT);
                        do {
                            final ContentValues values = new ContentValues(8);
                            final String parentTripPath = getString(c, parentTripIndex, "");
                            final String location = getString(c, locationIndex, "");
                            final BigDecimal distance = getDecimal(c, distanceIndex);
                            final BigDecimal rate = getDecimal(c, rateIndex);
                            final String currency = getString(c, currencyIndex, mPersistenceManager.getPreferences().getDefaultCurreny());
                            final long date = getLong(c, dateIndex, 0L);
                            final String timezone = getString(c, timezoneIndex, TimeZone.getDefault().getID());
                            final String comment = getString(c, commentIndex, "");
                            values.put(DistanceTable.COLUMN_PARENT, parentTripPath);
                            values.put(DistanceTable.COLUMN_LOCATION, location);
                            values.put(DistanceTable.COLUMN_DISTANCE, distance.doubleValue());
                            values.put(DistanceTable.COLUMN_RATE, rate.doubleValue());
                            values.put(DistanceTable.COLUMN_RATE_CURRENCY, currency);
                            values.put(DistanceTable.COLUMN_DATE, date);
                            values.put(DistanceTable.COLUMN_TIMEZONE, timezone);
                            values.put(DistanceTable.COLUMN_COMMENT, comment);
                            try {
                                countCursor = currDB.rawQuery(distanceCountQuery, new String[]{parentTripPath, location, Long.toString(date)});
                                if (countCursor != null && countCursor.moveToFirst()) {
                                    int count = countCursor.getInt(0);
                                    int updateID = countCursor.getInt(1);
                                    if (count > 0 && overwrite) { // Update
                                        currDB.update(DistanceTable.TABLE_NAME, values, DistanceTable.COLUMN_ID + " = ?", new String[]{Integer.toString(updateID)});
                                    } else { // insert
                                        if (overwrite) {
                                            currDB.insertWithOnConflict(DistanceTable.TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE);
                                        } else if (count == 0) {
                                            // If we're not overwriting anything, let's check that there are no entries here
                                            currDB.insertWithOnConflict(DistanceTable.TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_IGNORE);
                                        }
                                    }
                                }
                            } finally {
                                if (countCursor != null && !countCursor.isClosed()) {
                                    countCursor.close();
                                    countCursor = null;
                                }
                            }
                        }
                        while (c.moveToNext());
                    }
                } catch (SQLiteException e) {
                    if (BuildConfig.DEBUG) {
                        Log.e(TAG, e.toString(), e); // Occurs if Table does not exist
                    }
                    mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Caught sql exception during import at [a6]: " + Utils.getStackTrace(e));
                } finally {
                    if (c != null && !c.isClosed()) {
                        c.close();
                        c = null;
                    }
                }

                mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Success");
                return true;
            } catch (Exception e) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, e.toString());
                }
                mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Caught fatal db exception during import at [a7]: " + Utils.getStackTrace(e));
                return false;
            } finally {
                if (c != null && !c.isClosed()) {
                    c.close();
                }
                if (countCursor != null && !countCursor.isClosed()) {
                    countCursor.close();
                }
                if (importDB != null) {
                    importDB.close();
                }
            }
        }
    }

    private boolean getBoolean(Cursor cursor, int index, boolean defaultValue) {
        if (index >= 0) {
            return (cursor.getInt(index) > 0);
        } else {
            return defaultValue;
        }
    }

    private int getInt(Cursor cursor, int index, int defaultValue) {
        if (index >= 0) {
            return cursor.getInt(index);
        } else {
            return defaultValue;
        }
    }

    private long getLong(Cursor cursor, int index, long defaultValue) {
        if (index >= 0) {
            return cursor.getLong(index);
        } else {
            return defaultValue;
        }
    }

    private double getDouble(Cursor cursor, int index, double defaultValue) {
        if (index >= 0) {
            return cursor.getDouble(index);
        } else {
            return defaultValue;
        }
    }

    private String getString(Cursor cursor, int index, String defaultValue) {
        if (index >= 0) {
            return cursor.getString(index);
        } else {
            return defaultValue;
        }
    }

    /**
     * Please note that a very frustrating bug exists here. Android cursors only return the first 6
     * characters of a price string if that string contains a '.' character. It returns all of them
     * if not. This means we'll break for prices over 5 digits unless we are using a comma separator,
     * which we'd do in the EU. In the EU (comma separated), Android returns the wrong value when we
     * get a double (instead of a string). This method has been built to handle this edge case to the
     * best of our abilities.
     * <p/>
     * TODO: Longer term, everything should be saved with a decimal point
     *
     * @param cursor - the current {@link android.database.Cursor}
     * @param index  - the index of the column
     * @return a {@link java.math.BigDecimal} value of the decimal
     * @see "https://code.google.com/p/android/issues/detail?id=22219."
     */
    private BigDecimal getDecimal(@NonNull Cursor cursor, int index) {
        return getDecimal(cursor, index, new BigDecimal(0));
    }

    private BigDecimal getDecimal(@NonNull Cursor cursor, int index, @Nullable BigDecimal defaultValue) {
        if (index >= 0) {
            final String decimalString = cursor.getString(index);
            final double decimalDouble = cursor.getDouble(index);
            if (!TextUtils.isEmpty(decimalString) && decimalString.contains(",")) {
                try {
                    return new BigDecimal(decimalString.replace(",", "."));
                } catch (NumberFormatException e) {
                    return new BigDecimal(decimalDouble);
                }
            } else {
                return new BigDecimal(decimalDouble);
            }
        } else {
            return defaultValue;
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // AutoCompleteTextView Methods
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    public void registerReceiptAutoCompleteListener(ReceiptAutoCompleteListener listener) {
        mReceiptAutoCompleteListener = listener;
    }

    public void unregisterReceiptAutoCompleteListener() {
        mReceiptAutoCompleteListener = null;
    }

    @Override
    public Cursor getAutoCompleteCursor(CharSequence text, CharSequence tag) {
        // TODO: Fix SQL vulnerabilities
        final SQLiteDatabase db = this.getReadableDatabase();
        String sqlQuery = "";
        if (tag == TAG_RECEIPTS_NAME) {
            sqlQuery = " SELECT DISTINCT TRIM(" + ReceiptsTable.COLUMN_NAME + ") AS _id " + " FROM " + ReceiptsTable.TABLE_NAME + " WHERE " + ReceiptsTable.COLUMN_NAME + " LIKE '%" + text + "%' " + " ORDER BY " + ReceiptsTable.COLUMN_NAME;
        } else if (tag == TAG_RECEIPTS_COMMENT) {
            sqlQuery = " SELECT DISTINCT TRIM(" + ReceiptsTable.COLUMN_COMMENT + ") AS _id " + " FROM " + ReceiptsTable.TABLE_NAME + " WHERE " + ReceiptsTable.COLUMN_COMMENT + " LIKE '%" + text + "%' " + " ORDER BY " + ReceiptsTable.COLUMN_COMMENT;
        } else if (tag == TAG_TRIPS_NAME) {
            sqlQuery = " SELECT DISTINCT TRIM(" + TripsTable.COLUMN_NAME + ") AS _id " + " FROM " + TripsTable.TABLE_NAME + " WHERE " + TripsTable.COLUMN_NAME + " LIKE '%" + text + "%' " + " ORDER BY " + TripsTable.COLUMN_NAME;
        } else if (tag == TAG_TRIPS_COST_CENTER) {
            sqlQuery = " SELECT DISTINCT TRIM(" + TripsTable.COLUMN_COST_CENTER + ") AS _id " + " FROM " + TripsTable.TABLE_NAME + " WHERE " + TripsTable.COLUMN_COST_CENTER + " LIKE '%" + text + "%' " + " ORDER BY " + TripsTable.COLUMN_COST_CENTER;
        } else if (tag == TAG_DISTANCE_LOCATION) {
            sqlQuery = " SELECT DISTINCT TRIM(" + DistanceTable.COLUMN_LOCATION + ") AS _id " + " FROM " + DistanceTable.TABLE_NAME + " WHERE " + DistanceTable.COLUMN_LOCATION + " LIKE '%" + text + "%' " + " ORDER BY " + DistanceTable.COLUMN_LOCATION;
        }
        synchronized (mDatabaseLock) {
            return db.rawQuery(sqlQuery, null);
        }
    }

    @Override
    public void onItemSelected(CharSequence text, CharSequence tag) {
        // TODO: Make Async

        Cursor c = null;
        SQLiteDatabase db = null;
        final String name = text.toString();
        if (tag == TAG_RECEIPTS_NAME) {
            String category = null, price = null, tmp = null;
            // If we're not predicting, return
            if (!mPersistenceManager.getPreferences().predictCategories()) {
                // price = null;
                // category = null
            } else {
                synchronized (mDatabaseLock) {
                    try {
                        db = this.getReadableDatabase();
                        c = db.query(ReceiptsTable.TABLE_NAME, new String[]{ReceiptsTable.COLUMN_CATEGORY, ReceiptsTable.COLUMN_PRICE}, ReceiptsTable.COLUMN_NAME + "= ?", new String[]{name}, null, null, ReceiptsTable.COLUMN_DATE + " DESC", "2");
                        if (c != null && c.getCount() == 2) {
                            if (c.moveToFirst()) {
                                category = c.getString(0);
                                price = c.getString(1);
                                if (c.moveToNext()) {
                                    tmp = c.getString(0);
                                    if (!category.equalsIgnoreCase(tmp)) {
                                        category = null;
                                    }
                                    tmp = c.getString(1);
                                    if (!price.equalsIgnoreCase(tmp)) {
                                        price = null;
                                    }
                                }
                            }
                        }
                    } finally {
                        if (c != null) {
                            c.close();
                        }
                    }
                }
            }
            if (mReceiptAutoCompleteListener != null) {
                mReceiptAutoCompleteListener.onReceiptRowAutoCompleteQueryResult(name, price, category);
            }
        }
    }

}
