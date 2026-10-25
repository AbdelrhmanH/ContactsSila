package app.sila.core.common

/**
 * أخطاء البيانات الموحدة عبر الطبقات (ARCHITECTURE.md §9).
 */
sealed interface DataError {
    data class Network(val message: String? = null) : DataError
    data class Permission(val permissionName: String) : DataError
    data class NotFound(val entityId: String? = null) : DataError
    data class Conflict(val reason: String) : DataError
    data class Validation(val field: String, val reason: String) : DataError
    data class Unknown(val causeMessage: String? = null) : DataError
}

/**
 * غلاف النتيجة الموحد لجميع عمليات المستودعات وحالات الاستخدام.
 */
sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Error(val error: DataError) : AppResult<Nothing>
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Error -> this
}

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(data)
    return this
}

inline fun <T> AppResult<T>.onError(action: (DataError) -> Unit): AppResult<T> {
    if (this is AppResult.Error) action(error)
    return this
}

fun <T> AppResult<T>.getOrNull(): T? = when (this) {
    is AppResult.Success -> data
    is AppResult.Error -> null
}
