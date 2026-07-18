const fs = require('fs');
let content = fs.readFileSync('app/src/main/java/com/example/MainActivity.kt', 'utf8');

// Replace Color.White with MaterialTheme.colorScheme.surface or background
content = content.replace(/color = Color\.White/g, 'color = MaterialTheme.colorScheme.surface');
content = content.replace(/containerColor = Color\.White/g, 'containerColor = MaterialTheme.colorScheme.surface');
content = content.replace(/\.background\(Color\.White\)/g, '.background(MaterialTheme.colorScheme.surface)');
content = content.replace(/\.background\(Color\.White, /g, '.background(MaterialTheme.colorScheme.surface, ');
content = content.replace(/tint = Color\.White/g, 'tint = MaterialTheme.colorScheme.onPrimary');
content = content.replace(/contentColor = Color\.White/g, 'contentColor = MaterialTheme.colorScheme.onPrimary');

// Replace specific bright hex colors with MaterialTheme alternatives
content = content.replace(/Color\(0xFF1B1B1F\)/g, 'MaterialTheme.colorScheme.onSurface');
content = content.replace(/Color\(0xFF1D192B\)/g, 'MaterialTheme.colorScheme.onSurface');
content = content.replace(/Color\(0xFF44474E\)/g, 'MaterialTheme.colorScheme.onSurfaceVariant');
content = content.replace(/Color\(0xFF74777F\)/g, 'MaterialTheme.colorScheme.onSurfaceVariant');
content = content.replace(/Color\(0xFF005AC1\)/g, 'MaterialTheme.colorScheme.primary');
content = content.replace(/Color\(0xFFE3E3E3\)/g, 'MaterialTheme.colorScheme.surfaceVariant');
content = content.replace(/Color\(0xFFF3F4F9\)/g, 'MaterialTheme.colorScheme.surfaceVariant');
content = content.replace(/Color\(0xFFE8DEF8\)/g, 'MaterialTheme.colorScheme.primaryContainer');
content = content.replace(/Color\(0xFFD3E4FF\)/g, 'MaterialTheme.colorScheme.primaryContainer');

// Surface copy alphas
content = content.replace(/MaterialTheme\.colorScheme\.surface\.copy/g, 'MaterialTheme.colorScheme.surfaceVariant.copy');

fs.writeFileSync('app/src/main/java/com/example/MainActivity.kt', content);
console.log("Colors replaced in MainActivity.kt");
