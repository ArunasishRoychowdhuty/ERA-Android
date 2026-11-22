package com.eraassistant.mobile.shizuku

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException

/**
 * Small explicit Binder contract for ERA's Shizuku UserService.
 * Kept wire-compatible with the matching AIDL transaction layout without
 * requiring the host-side AIDL compiler in the Android-on-device build.
 */
interface IEraShizukuUserService : IInterface {
    @Throws(RemoteException::class)
    fun executeReadOnly(operationId: String, argument: String): String

    abstract class Stub : Binder(), IEraShizukuUserService {
        init {
            attachInterface(this, DESCRIPTOR)
        }

        override fun asBinder(): IBinder = this

        @Throws(RemoteException::class)
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(DESCRIPTOR)
                    return true
                }
                TRANSACTION_DESTROY -> {
                    data.enforceInterface(DESCRIPTOR)
                    reply?.writeNoException()
                    destroy()
                    return true
                }
                TRANSACTION_EXECUTE_READ_ONLY -> {
                    data.enforceInterface(DESCRIPTOR)
                    val operationId = data.readString().orEmpty()
                    val argument = data.readString().orEmpty()
                    val result = executeReadOnly(operationId, argument)
                    reply?.writeNoException()
                    reply?.writeString(result)
                    return true
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
        }

        open fun destroy() = Unit

        companion object {
            private const val DESCRIPTOR = "com.eraassistant.mobile.shizuku.IEraShizukuUserService"
            private const val TRANSACTION_EXECUTE_READ_ONLY = IBinder.FIRST_CALL_TRANSACTION + 0
            private const val TRANSACTION_DESTROY = 16777114

            fun asInterface(binder: IBinder?): IEraShizukuUserService? {
                if (binder == null) return null
                val local = binder.queryLocalInterface(DESCRIPTOR)
                return if (local is IEraShizukuUserService) local else Proxy(binder)
            }
        }

        private class Proxy(private val remote: IBinder) : IEraShizukuUserService {
            override fun asBinder(): IBinder = remote

            @Throws(RemoteException::class)
            override fun executeReadOnly(operationId: String, argument: String): String {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(operationId)
                    data.writeString(argument)
                    check(remote.transact(TRANSACTION_EXECUTE_READ_ONLY, data, reply, 0)) {
                        "Shizuku service does not implement read-only diagnostics."
                    }
                    reply.readException()
                    return reply.readString().orEmpty()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }
        }
    }
}
