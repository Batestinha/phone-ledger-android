package app.phoneledger.android.autofill

import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.util.Log
import app.phoneledger.android.storage.LedgerRepository

class PhoneLedgerAutofillService : AutofillService() {
    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        try {
            if (cancellationSignal.isCanceled || request.fillContexts.isEmpty()) return
            val finder = PhoneFieldFinder(request.fillContexts.last().structure)
            finder.parse()
            if (finder.fields.isEmpty()) {
                callback.onSuccess(null)
                return
            }
            val target = finder.target(this)
            if (target.clientPackage == packageName) {
                callback.onSuccess(null)
                return
            }
            LedgerRepository.initialize(this)
            val response = FillResponse.Builder()
            if (LedgerRepository.isUnlocked()) {
                LedgerRepository.rankedPhones(target).take(MAX_INLINE_NUMBERS).forEach {
                    response.addDataset(DatasetFactory.phone(this, finder.fields, target, it))
                }
                response.addDataset(DatasetFactory.picker(this, finder.fields, target, locked = false))
            } else {
                response.addDataset(DatasetFactory.picker(this, finder.fields, target, locked = true))
            }
            callback.onSuccess(response.build())
        } catch (error: Exception) {
            Log.e(TAG, "Unable to build phone autofill response", error)
            callback.onFailure("Phone Ledger could not read the form")
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) = callback.onSuccess()

    companion object {
        private const val TAG = "PhoneLedgerAutofill"
        private const val MAX_INLINE_NUMBERS = 8
    }
}
