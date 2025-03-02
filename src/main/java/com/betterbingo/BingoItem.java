package com.betterbingo;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BingoItem
{
    @EqualsAndHashCode.Include
    private final String name;
    private boolean obtained;
    private int itemId;

    public BingoItem(String name)
    {
        this.name = name;
        this.obtained = false;
        this.itemId = -1;
    }

    public BingoItem(String name, int itemId)
    {
        this.name = name;
        this.obtained = false;
        this.itemId = itemId;
    }
} 