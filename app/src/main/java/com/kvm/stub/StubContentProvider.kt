package com.kvm.stub

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import timber.log.Timber

open class StubContentProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        Timber.d("StubContentProvider [%s] onCreate", javaClass.simpleName)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0

    class P00 : StubContentProvider()
    class P01 : StubContentProvider()
    class P02 : StubContentProvider()
}
