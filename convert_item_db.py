"""
MC Item Database Converter
Convert an Excel file (Chinese name, ID) to JSON for the StockExchange plugin.

Usage:
    python convert_item_db.py <input.xlsx> [output.json]

Excel format:
    Column A: Item Chinese name (物品中文名)
    Column B: Item ID (纯ID, e.g. diamond, diamond_sword)
"""

import json
import sys
import os

try:
    import openpyxl
except ImportError:
    print("Please install openpyxl first: pip install openpyxl")
    sys.exit(1)


def convert_excel_to_json(excel_path, output_path=None):
    """Convert the Excel item database to JSON."""
    
    if not os.path.exists(excel_path):
        print(f"Error: File not found: {excel_path}")
        sys.exit(1)
    
    # Default output path: same directory as input, named item_database.json
    if output_path is None:
        output_dir = os.path.dirname(excel_path)
        output_path = os.path.join(output_dir, "item_database.json")
    
    wb = openpyxl.load_workbook(excel_path, data_only=True)
    ws = wb.active
    
    items = []
    seen_ids = set()
    
    for row in ws.iter_rows(min_row=2, values_only=True):
        chinese_name = str(row[0]).strip() if row[0] is not None else ""
        item_id = str(row[1]).strip().lower() if row[1] is not None else ""
        
        # Skip empty rows
        if not chinese_name and not item_id:
            continue
        
        # Clean Chinese name: remove [新增: ...] markers
        import re
        chinese_name = re.sub(r'\[.*?\]', '', chinese_name).strip()
        
        if not chinese_name or not item_id:
            continue
        
        # Deduplicate by ID (keep first occurrence)
        if item_id not in seen_ids:
            seen_ids.add(item_id)
            items.append({
                "name": chinese_name,
                "id": item_id
            })
    
    wb.close()
    
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(items, f, ensure_ascii=False, indent=2)
    
    print(f"Done! Converted {len(items)} items")
    print(f"Output: {output_path}")
    
    # Show sample
    for item in items[:5]:
        print(f"  {item['id']:30s} -> {item['name']}")
    
    return items


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    
    excel_path = sys.argv[1]
    output_path = sys.argv[2] if len(sys.argv) > 2 else None
    
    convert_excel_to_json(excel_path, output_path)
