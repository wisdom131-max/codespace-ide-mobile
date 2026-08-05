"""alpha_main.py — Main entry point for testing editor features."""


def calculate_sum(a, b):
    """Calculate the sum of two numbers."""
    return a + b


def calculate_product(a, b):
    """Calculate the product of two numbers."""
    return a * b


class Calculator:
    """A simple calculator class."""

    def __init__(self):
        self.result = 0

    def add(self, x, y):
        """Add two numbers and store the result."""
        self.result = calculate_sum(x, y)
        return self.result

    def multiply(self, x, y):
        """Multiply two numbers and store the result."""
        self.result = calculate_product(x, y)
        return self.result

    def clear(self):
        """Reset the calculator."""
        self.result = 0


def main():
    """Run the calculator demo."""
    calc = Calculator()
    total = calc.add(3, 5)
    product = calc.multiply(4, 6)
    print(f"Sum: {total}")
    print(f"Product: {product}")


if __name__ == "__main__":
    main()
