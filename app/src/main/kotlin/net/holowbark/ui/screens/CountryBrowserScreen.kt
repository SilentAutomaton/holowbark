package net.holowbark.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.holowbark.peers.models.CountryInfo
import net.holowbark.ui.TunnelViewModel
import net.holowbark.ui.contentWidth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountryBrowserScreen(
    vm: TunnelViewModel,
    onCountrySelected: (countryKey: String) -> Unit,
    onBack: () -> Unit,
) {
    val countries  by vm.countries.collectAsState()
    val isLoading  by vm.isLoadingPeers.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Public peers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refreshCountries(force = true) }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (countries.isEmpty()) {
                EmptyState(onRefresh = { vm.refreshCountries(force = true) })
            } else {
                CountryList(countries = countries, onCountrySelected = onCountrySelected)
            }
        }
    }
}

@Composable
private fun CountryList(
    countries: List<CountryInfo>,
    onCountrySelected: (String) -> Unit,
) {
    // Group by region
    val grouped = remember(countries) { countries.groupBy { it.regionSlug } }

    LazyColumn(Modifier.contentWidth()) {
        grouped.forEach { (region, items) ->
            item(key = "header_$region") {
                Text(
                    text = region.replaceFirstChar { it.uppercaseChar() },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(items, key = { it.countryKey }) { info ->
                CountryRow(info = info, onClick = { onCountrySelected(info.countryKey) })
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun CountryRow(info: CountryInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(info.displayName, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            info.upPeers?.let {
                Text(
                    "$it↑ /",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "${info.totalPeers}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun EmptyState(onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No peer data", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("Pull from the network to populate the list.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRefresh) { Text("Fetch peers") }
    }
}
