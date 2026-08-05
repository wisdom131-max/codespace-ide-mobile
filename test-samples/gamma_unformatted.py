"""gamma_unformatted.py — Poorly formatted code for testing code folding and formatting."""

import os
import sys

class DataProcessor:
            """Process data items."""
            def __init__(self):
                        self.items=[]
                        self.count=0
            def add_item(self,item):
                        """Add an item to the processor."""
                        self.items.append(item)
                        self.count+=1
            def get_items(self):
                        """Return all items."""
                        return self.items

def long_function_with_many_lines(a,b,c,d,e):
    """This function has many parameters and lines to test folding."""
    result=a+b
    result=result+c
    result=result+d
    result=result+e
    result=result*2
    return result

def another_function():
    x=1
    y=2
    z=x+y
    return z
