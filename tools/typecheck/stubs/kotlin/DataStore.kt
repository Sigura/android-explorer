// androidx.datastore 1.1.4 (core + preferences), the API the app uses.
package androidx.datastore.core

import kotlinx.coroutines.flow.Flow

interface DataStore<T> {
    val data: Flow<T>
    suspend fun updateData(transform: suspend (t: T) -> T): T
}
