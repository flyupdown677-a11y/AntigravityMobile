package com.antigravity.mobile.data.repository

import android.content.Context
import com.antigravity.mobile.data.model.Account
import com.antigravity.mobile.utils.SecurityHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class AccountRepository(context: Context) {

    private val securityHelper = SecurityHelper(context)

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _activeAccount = MutableStateFlow<Account?>(null)
    val activeAccount: StateFlow<Account?> = _activeAccount.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val list = securityHelper.loadAccounts()
        _accounts.value = list
        _activeAccount.value = securityHelper.getActiveAccount()
    }

    fun addAccount(name: String, email: String, apiKey: String, model: String = "gemini-2.5-flash"): Account {
        val current = _accounts.value.toMutableList()
        val isFirst = current.isEmpty()
        val newAccount = Account(
            id = UUID.randomUUID().toString(),
            name = name,
            email = email,
            apiKey = apiKey,
            modelName = model,
            isActive = isFirst
        )
        current.add(newAccount)
        securityHelper.saveAccounts(current)
        refresh()
        return newAccount
    }

    fun switchAccount(accountId: String) {
        securityHelper.setActiveAccount(accountId)
        refresh()
    }

    fun deleteAccount(accountId: String) {
        val current = _accounts.value.filter { it.id != accountId }
        securityHelper.saveAccounts(current)
        if (_activeAccount.value?.id == accountId) {
            val fallback = current.firstOrNull()?.id
            if (fallback != null) {
                securityHelper.setActiveAccount(fallback)
            }
        }
        refresh()
    }
}
