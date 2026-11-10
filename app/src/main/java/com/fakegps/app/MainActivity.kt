package com.fakegps.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fakegps.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: FavoritesStore
    private lateinit var marker: Marker
    private var selected: GeoPoint? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            startMocking()
        } else {
            toast(getString(R.string.permission_needed))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = cacheDir
            osmdroidTileCache = cacheDir.resolve("tiles")
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = FavoritesStore(this)

        setupMap()
        setupControls()
        observeService()
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
    }

    override fun onPause() {
        binding.map.onPause()
        super.onPause()
    }

    private fun setupMap() = with(binding.map) {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        controller.setZoom(16.0)

        marker = Marker(this).apply {
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_pin)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            isDraggable = true
            setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                override fun onMarkerDrag(m: Marker) {}
                override fun onMarkerDragStart(m: Marker) {}
                override fun onMarkerDragEnd(m: Marker) = selectLocation(m.position, recenter = false)
            })
            setOnMarkerClickListener { _, _ -> true }
        }

        overlays += MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                selectLocation(p, recenter = false)
                return true
            }
            override fun longPressHelper(p: GeoPoint) = false
        })

        val running = MockLocationService.state.value as? MockLocationService.State.Running
        val start = if (running != null) GeoPoint(running.lat, running.lng)
        else GeoPoint(store.lastLat, store.lastLng)
        selectLocation(start, recenter = true)
    }

    private fun setupControls() = with(binding) {
        toggleButton.setOnClickListener {
            if (MockLocationService.state.value is MockLocationService.State.Running) {
                MockLocationService.stop(this@MainActivity)
            } else {
                requestPermissionsAndStart()
            }
        }
        searchButton.setOnClickListener { search() }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
        }
        saveFavoriteButton.setOnClickListener { saveFavorite() }
        favoritesButton.setOnClickListener { showFavorites() }
        myLocationButton.setOnClickListener { goToRealLocation() }
    }

    private fun observeService() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MockLocationService.state.collect { state ->
                    when (state) {
                        is MockLocationService.State.Running -> {
                            binding.toggleButton.text = getString(R.string.stop)
                            binding.toggleButton.setBackgroundColor(getColor(R.color.danger))
                            binding.statusText.text = getString(R.string.notif_title)
                            binding.statusText.setTextColor(getColor(R.color.danger))
                        }
                        else -> {
                            binding.toggleButton.text = getString(R.string.start)
                            binding.toggleButton.setBackgroundColor(getColor(R.color.primary))
                            binding.statusText.text = getString(R.string.tap_map_hint)
                            binding.statusText.setTextColor(getColor(R.color.text_secondary))
                            if (state is MockLocationService.State.MockNotAllowed) showMockSetupDialog()
                        }
                    }
                }
            }
        }
    }

    private fun selectLocation(point: GeoPoint, recenter: Boolean) {
        selected = point
        marker.position = point
        if (!binding.map.overlays.contains(marker)) binding.map.overlays += marker
        if (recenter) binding.map.controller.animateTo(point)
        binding.map.invalidate()
        binding.coordText.text = String.format(Locale.US, "%.6f, %.6f", point.latitude, point.longitude)
        binding.addressText.text = ""
        store.lastLat = point.latitude
        store.lastLng = point.longitude

        // Update a running mock immediately so the user can "teleport" without stopping first.
        if (MockLocationService.state.value is MockLocationService.State.Running) {
            MockLocationService.start(this, point.latitude, point.longitude)
        }
        lifecycleScope.launch {
            val address = reverseGeocode(point)
            if (selected == point) binding.addressText.text = address.orEmpty()
        }
    }

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startMocking() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startMocking() {
        val point = selected ?: return toast(getString(R.string.select_location_first))
        MockLocationService.start(this, point.latitude, point.longitude)
    }

    private fun showMockSetupDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.mock_not_enabled_title)
            .setMessage(R.string.mock_not_enabled_msg)
            .setPositiveButton(R.string.open_dev_settings) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { MockLocationService.resetError() }
            .show()
    }

    private fun search() {
        val query = binding.searchInput.text.toString().trim()
        if (query.isEmpty()) return
        hideKeyboard()

        parseCoordinates(query)?.let {
            selectLocation(it, recenter = true)
            return
        }
        lifecycleScope.launch {
            val result = geocode(query)
            if (result == null) toast(getString(R.string.not_found))
            else selectLocation(result, recenter = true)
        }
    }

    /** Accepts "lat, lng" or "lat lng". */
    private fun parseCoordinates(text: String): GeoPoint? {
        val parts = text.split(',', ' ').filter { it.isNotBlank() }
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lng = parts[1].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return GeoPoint(lat, lng)
    }

    private suspend fun geocode(query: String): GeoPoint? = withContext(Dispatchers.IO) {
        if (Geocoder.isPresent()) {
            try {
                @Suppress("DEPRECATION")
                Geocoder(this@MainActivity).getFromLocationName(query, 1)?.firstOrNull()?.let {
                    return@withContext GeoPoint(it.latitude, it.longitude)
                }
            } catch (_: Exception) {
            }
        }
        // Fallback: OpenStreetMap Nominatim.
        try {
            val url = URL(
                "https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" +
                    URLEncoder.encode(query, "UTF-8")
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "$packageName/1.0")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            if (arr.length() == 0) null
            else arr.getJSONObject(0).let { GeoPoint(it.getString("lat").toDouble(), it.getString("lon").toDouble()) }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun reverseGeocode(point: GeoPoint): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        try {
            @Suppress("DEPRECATION")
            Geocoder(this@MainActivity).getFromLocation(point.latitude, point.longitude, 1)
                ?.firstOrNull()?.getAddressLine(0)
        } catch (_: Exception) {
            null
        }
    }

    private fun saveFavorite() {
        val point = selected ?: return toast(getString(R.string.select_location_first))
        val input = EditText(this).apply {
            hint = getString(R.string.favorite_name)
            setText(binding.addressText.text.toString().substringBefore(','))
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.save_favorite)
            .setView(input)
            .setPositiveButton(R.string.save_favorite) { _, _ ->
                val name = input.text.toString().ifBlank { binding.coordText.text.toString() }
                store.add(Favorite(name, point.latitude, point.longitude))
                toast(getString(R.string.saved))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showFavorites() {
        val favorites = store.all()
        if (favorites.isEmpty()) return toast(getString(R.string.no_favorites))
        val labels = favorites.map {
            "${it.name}\n" + String.format(Locale.US, "%.5f, %.5f", it.lat, it.lng)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.favorites)
            .setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)) { _, i ->
                selectLocation(GeoPoint(favorites[i].lat, favorites[i].lng), recenter = true)
            }
            .setNeutralButton(R.string.delete) { _, _ -> showDeleteFavorites(favorites) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteFavorites(favorites: List<Favorite>) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setItems(favorites.map { it.name }.toTypedArray()) { _, i ->
                store.remove(favorites[i])
                toast(getString(R.string.delete) + ": " + favorites[i].name)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun goToRealLocation() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val loc = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        if (loc != null) binding.map.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
        else toast(getString(R.string.not_found))
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
