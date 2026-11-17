package com.example.flipperdroid.view

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.flipperdroid.viewmodel.NetworkToolsViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Network tools screen to test connectivity and scan ports
 *
 * The user can enter an IP address or a hostname
 * They can pick among several tools: ping, port scan, DNS lookup, traceroute, nmap
 * The command results are displayed in a list with error highlighting
 *
 * @param navController Navigation controller to handle going back
 * @param viewModel View model handling the network command logic and the state of the results
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkToolsScreen(
    navController: NavController,
    viewModel: NetworkToolsViewModel = viewModel()
) {
    val context = LocalContext.current
    // Initialize the ViewModel context (only once)
    LaunchedEffect(Unit) {
        viewModel.initialize(context)
    }

    // Local state for the selected tool
    var selectedTool by remember { mutableStateOf<String?>(null) }

    // IP address or hostname entered by the user
    var ipAddress by remember { mutableStateOf("") }

    // Options for the nmap command
    var parameter by remember { mutableStateOf("") }

    // Start port for the port scan
    var startPort by remember { mutableStateOf("1") }

    // End port for the port scan
    var endPort by remember { mutableStateOf("1024") }

    // Results collected from the view model
    val results by viewModel.results.collectAsState()

    // Current scan state
    val isScanning by viewModel.isScanning.collectAsState()

    Log.d("DEBUG", "Context path: ${context.filesDir}")


    Scaffold(
        topBar = {
            /**
             * Top bar with a back button and a clear-results button
             */
            TopAppBar(
                title = { Text("Network Tools") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearResults() }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear results")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            /**
             * Input field for the IP address or hostname
             */
            OutlinedTextField(
                value = ipAddress,
                onValueChange = { ipAddress = it },
                label = { Text("IP Address / Hostname") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            /**
             * Port-range input fields shown when the portscan tool is selected
             */
            if (selectedTool == "portscan") {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = startPort,
                        onValueChange = { startPort = it },
                        label = { Text("Start Port") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = endPort,
                        onValueChange = { endPort = it },
                        label = { Text("End Port") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (selectedTool == "nmap") {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = parameter,
                    onValueChange = { parameter = it },
                    label = { Text("Options Nmap (ex: -sV -T4)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }


            /**
             * List of cards representing the available network tools
             * Each card runs the matching command when an address has been entered
             */
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    ElevatedCard(
                        onClick = {
                            selectedTool = "ping"
                            if (ipAddress.isNotEmpty()) {
                                viewModel.ping(ipAddress)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ListItem(
                            headlineContent = { Text("Ping") },
                            supportingContent = { Text("Test host reachability") },
                            leadingContent = {
                                Icon(Icons.Default.NetworkPing, contentDescription = null)
                            }
                        )
                    }
                }

                item {
                    ElevatedCard(
                        onClick = {
                            selectedTool = "portscan"
                            if (ipAddress.isNotEmpty()) {
                                viewModel.scanPorts(
                                    ipAddress,
                                    startPort.toIntOrNull() ?: 1,
                                    endPort.toIntOrNull() ?: 1024
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ListItem(
                            headlineContent = { Text("Port Scanner") },
                            supportingContent = { Text("Scan for open ports") },
                            leadingContent = {
                                Icon(Icons.Default.Scanner, contentDescription = null)
                            }
                        )
                    }
                }

                item {
                    ElevatedCard(
                        onClick = {
                            selectedTool = "dns"
                            if (ipAddress.isNotEmpty()) {
                                viewModel.dnsLookup(ipAddress)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ListItem(
                            headlineContent = { Text("DNS Lookup") },
                            supportingContent = { Text("Resolve domain names") },
                            leadingContent = {
                                Icon(Icons.Default.Language, contentDescription = null)
                            }
                        )
                    }
                }

                item {
                    ElevatedCard(
                        onClick = {
                            selectedTool = "traceroute"
                            if (ipAddress.isNotEmpty()) {
                                viewModel.traceroute(ipAddress)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ListItem(
                            headlineContent = { Text("Traceroute") },
                            supportingContent = { Text("Trace network path") },
                            leadingContent = {
                                Icon(Icons.Default.Timeline, contentDescription = null)
                            }
                        )
                    }
                }

                item {
                    ElevatedCard(
                        onClick = {
                            selectedTool = "nmap"
                            if (ipAddress.isNotEmpty()) {
                                viewModel.nmap(ipAddress,parameter)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ListItem(
                            headlineContent = { Text("Nmap Scanner") },
                            supportingContent = { Text("Advanced port and service scanner") },
                            leadingContent = {
                                Icon(Icons.Default.Search, contentDescription = null)
                            }
                        )
                    }
                }

                item {
                    ElevatedCard(
                        onClick = { viewModel.pingSweep(ipAddress) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ListItem(
                            headlineContent = { Text("Ping Sweep") },
                            supportingContent = { Text("Find live hosts on the /24 of the IP above") },
                            leadingContent = { Icon(Icons.Default.Lan, contentDescription = null) }
                        )
                    }
                }

                item {
                    ElevatedCard(
                        onClick = { viewModel.arpTable() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ListItem(
                            headlineContent = { Text("ARP Table") },
                            supportingContent = { Text("Local network neighbors (IP -> MAC)") },
                            leadingContent = { Icon(Icons.Default.DeviceHub, contentDescription = null) }
                        )
                    }
                }

                item {
                    ElevatedCard(
                        onClick = { viewModel.wakeOnLan(ipAddress) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ListItem(
                            headlineContent = { Text("Wake-on-LAN") },
                            supportingContent = { Text("Send magic packet (put a MAC in the field above)") },
                            leadingContent = { Icon(Icons.Default.PowerSettingsNew, contentDescription = null) }
                        )
                    }
                }

                /**
                 * Display of results with error highlighting
                 */
                // CLEAR button visible if results are not empty
                    if (results.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = { viewModel.clearResults() }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Clear results")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("CLEAR")
                                }
                            }
                        }
                    }

                    // Display of the results
                    items(results) { result ->
                        val cardColor = if (result.isError)
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant

                        val textColor = if (result.isError)
                            MaterialTheme.colorScheme.onErrorContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant

                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.elevatedCardColors(containerColor = cardColor)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = result.command,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = textColor
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = result.output,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = textColor
                                )
                            }
                        }
                    }
                }

            /**
             * Linear progress bar shown while the scan runs
             */
            if (isScanning) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
