package co.smartreceipts.android.persistence.database.controllers.impl;

import javax.inject.Inject;

import co.smartreceipts.android.analytics.Analytics;
import co.smartreceipts.android.di.scopes.ApplicationScope;
import co.smartreceipts.android.model.PaymentMethod;
import co.smartreceipts.android.persistence.DatabaseHelper;

@ApplicationScope
public class PaymentMethodsTableController extends AbstractTableController<PaymentMethod> {

    @Inject
    public PaymentMethodsTableController(DatabaseHelper databaseHelper, Analytics analytics) {
        super(databaseHelper.getPaymentMethodsTable(), analytics);
    }
}
