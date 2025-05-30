/* Fetch prices and update the form */
fetch(`/api/config`)
  .then(r => r.json())
  .then(({basicPrice, proPrice}) => {
    const basicPriceInput = document.querySelector('#basicPrice');
    basicPriceInput.value = basicPrice;
    const proPriceInput = document.querySelector('#proPrice');
    proPriceInput.value = proPrice;
  })
  .catch(error => {
    console.error('Error fetching prices:', error);
    document.getElementById('error-message').textContent = 'Error loading prices. Please try again later.';
  });
