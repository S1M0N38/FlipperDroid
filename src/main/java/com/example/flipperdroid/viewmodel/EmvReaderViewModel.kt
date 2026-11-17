package com.example.flipperdroid.viewmodel

import android.nfc.Tag
import android.nfc.tech.IsoDep
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.flipperdroid.nfc.EmvCardData
import com.example.flipperdroid.nfc.EmvCardReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
/**
 * ViewModel handling EMV card reading over NFC
 *
 * This class uses an EMV reader to extract the data from the detected NFC card.
 * It exposes the data, the read state and any errors via StateFlow
 */
class EmvReaderViewModel : ViewModel() {
    private val emvCardReader = EmvCardReader()

    private val _cardData = MutableStateFlow<EmvCardData?>(null)
    val cardData: StateFlow<EmvCardData?> = _cardData

    private val _isReading = MutableStateFlow(false)
    val isReading: StateFlow<Boolean> = _isReading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /**
     * Starts reading the NFC card from the received tag
     *
     * Updates the read state, the retrieved data and any errors
     * A tag that is not ISO-DEP is silently ignored (it is not an EMV card).
     *
     * @param tag NFC tag detected by the system
     */
    fun readCard(tag: Tag) {
        if (IsoDep.get(tag) == null) return
        _cardData.value = null
        viewModelScope.launch {
            try {
                _isReading.emit(true)
                _error.emit(null)

                val data = emvCardReader.readCard(tag)
                if (data != null) {
                    _cardData.emit(data)
                } else {
                    _error.emit(
                        "No EMV data could be read. Make sure it's a contactless payment card, " +
                            "held steady against the NFC antenna. Some cards refuse contactless reads " +
                            "outside a real terminal."
                    )
                }
            } catch (e: Exception) {
                _error.emit("Error reading card: ${e.message}")
            } finally {
                _isReading.emit(false)
            }
        }
    }

    /**
     * Clears the data and errors in memory
     *
     * Resets the cardData and error flows to null
     */
    fun clearData() {
        viewModelScope.launch {
            _cardData.emit(null)
            _error.emit(null)
        }
    }
}
