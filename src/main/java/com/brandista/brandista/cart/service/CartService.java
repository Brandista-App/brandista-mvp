package com.brandista.brandista.cart.service;

import com.brandista.brandista.cart.Cart;
import com.brandista.brandista.cart.CartItem;
import com.brandista.brandista.cart.CartItemRepository;
import com.brandista.brandista.cart.CartRepository;
import com.brandista.brandista.cart.api.AddCartItemRequest;
import com.brandista.brandista.cart.api.CartItemResponse;
import com.brandista.brandista.cart.api.CartResponse;
import com.brandista.brandista.cart.api.UpdateCartItemRequest;
import com.brandista.brandista.product.Product;
import com.brandista.brandista.product.ProductAvailability;
import com.brandista.brandista.product.ProductPublicationStatus;
import com.brandista.brandista.product.ProductRepository;
import com.brandista.brandista.brand.BrandStatus;
import com.brandista.brandista.category.CategoryStatus;
import com.brandista.brandista.user.User;
import com.brandista.brandista.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;

    public CartService(CartRepository cartRepository, CartItemRepository cartItemRepository,
                       UserRepository userRepository, ProductRepository productRepository) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.userRepository = userRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public CartResponse get(String principalSubject) {
        return toResponse(requireCart(principalSubject));
    }

    @Transactional
    public CartMutationResult add(String principalSubject, AddCartItemRequest request) {
        Cart cart = requireCart(principalSubject);
        Product product = productRepository.findByIdAndPublicationStatusAndAvailabilityAndBrand_StatusAndCategory_Status(
                        request.productId(), ProductPublicationStatus.PUBLISHED, ProductAvailability.AVAILABLE,
                        BrandStatus.ACTIVE, CategoryStatus.ACTIVE)
                .orElseThrow(CartProductUnavailableException::new);

        CartItem item = cartItemRepository.findByCart_IdAndProduct_Id(cart.getId(), product.getId()).orElse(null);
        boolean itemCreated = item == null;
        if (item == null) {
            cart.addItem(new CartItem(product, request.quantity()));
        } else {
            long combined = (long) item.getQuantity() + request.quantity();
            if (combined > Integer.MAX_VALUE) throw new CartQuantityException();
            item.setQuantity((int) combined);
            cart.touch();
        }
        return new CartMutationResult(toResponse(cartRepository.saveAndFlush(cart)), itemCreated);
    }

    @Transactional
    public CartResponse update(String principalSubject, Long itemId, UpdateCartItemRequest request) {
        Cart cart = requireCart(principalSubject);
        CartItem item = cartItemRepository.findByIdAndCart_Id(itemId, cart.getId())
                .orElseThrow(CartNotFoundException::new);
        item.setQuantity(request.quantity());
        cart.touch();
        return toResponse(cartRepository.saveAndFlush(cart));
    }

    @Transactional
    public void remove(String principalSubject, Long itemId) {
        Cart cart = requireCart(principalSubject);
        CartItem item = cartItemRepository.findByIdAndCart_Id(itemId, cart.getId())
                .orElseThrow(CartNotFoundException::new);
        cart.removeItem(item);
        cartRepository.saveAndFlush(cart);
    }

    @Transactional
    public CartResponse clear(String principalSubject) {
        Cart cart = requireCart(principalSubject);
        cart.clearItems();
        return toResponse(cartRepository.saveAndFlush(cart));
    }

    private Cart requireCart(String principalSubject) {
        long customerId;
        try {
            customerId = Long.parseLong(principalSubject);
            if (customerId <= 0) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
            throw new CartNotFoundException();
        }

        User customer = userRepository.findByIdForUpdate(customerId).orElseThrow(CartNotFoundException::new);
        return cartRepository.findAggregateByCustomerId(customerId)
                .orElseGet(() -> cartRepository.saveAndFlush(new Cart(customer)));
    }

    private static CartResponse toResponse(Cart cart) {
        List<CartItemResponse> items = cart.getItems().stream()
                .map(item -> {
                    Product product = item.getProduct();
                    BigDecimal lineSubtotal = product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
                    boolean available = product.getPublicationStatus() == ProductPublicationStatus.PUBLISHED
                            && product.getAvailability() == ProductAvailability.AVAILABLE
                            && product.getBrand().getStatus() == BrandStatus.ACTIVE
                            && product.getCategory().getStatus() == CategoryStatus.ACTIVE;
                    return new CartItemResponse(item.getId(), product.getId(), product.getName(),
                            product.getBrand().getName(), product.getPrice(), item.getQuantity(), lineSubtotal, available);
                }).toList();
        BigDecimal subtotal = items.stream().map(CartItemResponse::lineSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartResponse(cart.getId(), items, subtotal, "PKR");
    }
}
