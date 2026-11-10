            Row(modifier = Modifier.fillMaxWidth()) {
                if (isTask) {
                    TaskCheckbox(
                        checked = item.checked == true,
                        modifier = Modifier.width(markerWidth).alignByBaseline(),
                    )
                } else {
                    Text(
                        text = markerText,
                        modifier = Modifier.width(markerWidth).alignByBaseline(),
                        style = baseStyle,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .alignByBaseline()
                        .padding(start = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
