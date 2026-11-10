            Row(modifier = Modifier.fillMaxWidth()) {
                if (isTask) {
                    TaskCheckbox(
                        checked = item.checked == true,
                        modifier = Modifier.width(markerWidth),
                    )
                } else {
                    Text(
                        text = markerText,
                        modifier = Modifier.width(markerWidth),
                        style = baseStyle,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
