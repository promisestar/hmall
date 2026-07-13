package com.hmall.cart.controller;



import com.hmall.cart.domain.dto.CartFormDTO;
import com.hmall.cart.domain.vo.CartVO;
import com.hmall.cart.service.ICartService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Api(tags = "购物车相关接口")
@RestController
@RequestMapping("/carts")
@RequiredArgsConstructor
public class CartController {
    private final ICartService cartService;

    @ApiOperation("添加商品到购物车")
    @PostMapping
    public void addItem2Cart(@Valid @RequestBody CartFormDTO cartFormDTO){
        cartService.addItem2Cart(cartFormDTO);
    }

    @ApiOperation("更新购物车商品数量")
    @PutMapping("{itemId}")
    public void updateCartNum(
            @PathVariable("itemId") Long itemId,
            @RequestBody Map<String, Integer> body) {
        cartService.updateCartNum(itemId, body.get("num"));
    }

    @ApiOperation("删除购物车中商品")
    @DeleteMapping("{itemId}")
    public void deleteCartItem(@PathVariable("itemId") Long itemId){
        cartService.removeByItemIds(Collections.singletonList(itemId));
    }

    @ApiOperation("查询购物车列表")
    @GetMapping
    public List<CartVO> queryMyCarts(){
        return cartService.queryMyCarts();
    }
    @ApiOperation("批量删除购物车中商品")
    @ApiImplicitParam(name = "ids", value = "购物车条目id集合")
    @DeleteMapping
    public void deleteCartItemByIds(@RequestParam("ids") List<Long> ids){
        cartService.removeByItemIds(ids);
    }
}
